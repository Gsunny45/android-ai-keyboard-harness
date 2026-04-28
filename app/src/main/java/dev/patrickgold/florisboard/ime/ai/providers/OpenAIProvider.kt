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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * OpenAI API provider.
 *
 * POST https://api.openai.com/v1/chat/completions
 * Uses the standard OpenAI SSE streaming format.
 */
class OpenAIProvider(
    override val config: ProviderConfig,
    private val apiKey: String,
) : Provider {

    override val id: String = "openai"
    override val displayName: String = "OpenAI"

    private val modelName = config.model ?: "gpt-4o-mini"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
        val requestBody = buildOpenAiRequest(request)

        val httpRequest = Request.Builder()
            .url(config.url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .build()

        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(httpRequest).execute()
                if (!response.isSuccessful) {
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

                while (!source.exhausted() && finishReason == null) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") { finishReason = FinishReason.STOP; break }

                    try {
                        val jsonObj = json.parseToJsonElement(data).jsonObject
                        val choices = jsonObj["choices"]?.jsonArray
                        if (choices != null && choices.isNotEmpty()) {
                            val delta = choices[0].jsonObject["delta"]?.jsonObject
                            val content = delta?.get("content")?.jsonPrimitive?.content
                            if (!content.isNullOrBlank()) {
                                trySend(Token(text = content))
                            }
                            val finish = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.content
                            if (finish != null && finish != "null" && finish != "") {
                                finishReason = when (finish) {
                                    "stop" -> FinishReason.STOP
                                    "length" -> FinishReason.LENGTH
                                    "content_filter" -> FinishReason.ERROR
                                    else -> FinishReason.STOP
                                }
                            }
                        }
                    } catch (_: Exception) { /* skip */ }
                }

                trySend(Token("", finishReason ?: FinishReason.STOP))
            } catch (e: Exception) {
                trySend(Token("", FinishReason.ERROR))
            } finally {
                close()
            }
        }
    }

    private fun buildOpenAiRequest(request: CompletionRequest): String {
        return buildString {
            append("""{
                |"model": "$modelName",
                |"messages": [
                |  {"role": "system", "content": ${jsonEncode(request.system)}},
                |  {"role": "user",   "content": ${jsonEncode(request.user)}}
                |],
                |"stream": true,
                |"temperature": ${request.temperature},
                |"max_tokens": ${request.maxTokens}
                |}""".trimMargin())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private fun jsonEncode(s: String): String =
            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    }
}
