package dev.patrickgold.florisboard.ime.ai.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude API provider.
 *
 * POST https://api.anthropic.com/v1/messages
 * Anthropic-SSE streaming format (text/event-stream).
 * Uses "anthropic-version: 2023-06-01" header.
 */
class AnthropicProvider(
    override val config: ProviderConfig,
    private val apiKey: String,
) : Provider {

    override val id: String = "anthropic"
    override val displayName: String = "Anthropic Claude"

    private val modelName = config.model ?: "claude-haiku-4-5-20251001"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
        val requestBody = buildAnthropicRequest(request)

        val httpRequest = Request.Builder()
            .url(config.url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .build()

        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(httpRequest).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    if (code == 429) {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 30
                        // Signal rate limit — close without sending more
                    }
                    trySend(Token("", FinishReason.ERROR))
                    close()
                    return@withContext
                }

                val source = response.body?.source() ?: run {
                    trySend(Token("", FinishReason.ERROR))
                    close()
                    return@withContext
                }

                var finishReason: FinishReason? = null
                var currentContentBlock: String? = null

                while (!source.exhausted() && finishReason == null) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") { finishReason = FinishReason.STOP; break }

                    try {
                        val jsonObj = json.parseToJsonElement(data).jsonObject
                        val type = jsonObj["type"]?.jsonPrimitive?.content

                        when (type) {
                            "content_block_start" -> {
                                currentContentBlock = jsonObj["index"]?.jsonPrimitive?.content
                            }
                            "content_block_delta" -> {
                                val delta = jsonObj["delta"]?.jsonObject
                                val text = delta?.get("text")?.jsonPrimitive?.content
                                if (!text.isNullOrBlank()) {
                                    trySend(Token(text = text))
                                }
                            }
                            "message_delta" -> {
                                val delta = jsonObj["delta"]?.jsonObject
                                val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                                if (stopReason != null) {
                                    finishReason = when (stopReason) {
                                        "end_turn" -> FinishReason.STOP
                                        "max_tokens" -> FinishReason.LENGTH
                                        "stop_sequence" -> FinishReason.STOP
                                        else -> FinishReason.STOP
                                    }
                                }
                            }
                            "error" -> {
                                val error = jsonObj["error"]?.jsonObject
                                val msg = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                                finishReason = FinishReason.ERROR
                            }
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE lines
                    }
                }

                trySend(Token("", finishReason ?: FinishReason.STOP))
            } catch (e: Exception) {
                trySend(Token("", FinishReason.ERROR))
            } finally {
                close()
            }
        }
    }

    private fun buildAnthropicRequest(request: CompletionRequest): String {
        return buildString {
            append("""{
                |"model": "$modelName",
                |"max_tokens": ${request.maxTokens},
                |"system": ${jsonEncode(request.system)},
                |"messages": [
                |  {"role": "user", "content": ${jsonEncode(request.user)}}
                |],
                |"stream": true
                |}""".trimMargin())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private fun jsonEncode(s: String): String =
            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    }
}
