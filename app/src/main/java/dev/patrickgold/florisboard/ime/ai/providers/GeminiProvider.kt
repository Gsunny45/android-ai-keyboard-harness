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
 * Google Gemini API provider.
 *
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={API_KEY}
 * Uses the Gemini streaming format with a different response structure than OpenAI.
 *
 * Response format (SSE stream):
 *   data: {"candidates":[{"content":{"parts":[{"text":"..."}],"role":"model"},"finishReason":"STOP"}]}
 */
class GeminiProvider(
    override val config: ProviderConfig,
    private val apiKey: String,
) : Provider {

    override val id: String = "gemini"
    override val displayName: String = "Google Gemini"

    private val modelName = config.model ?: "gemini-2.0-flash"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
        val streamUrl = "${config.url}?alt=sse&key=$apiKey"
        val requestBody = buildGeminiRequest(request)

        val httpRequest = Request.Builder()
            .url(streamUrl)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .build()

        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(httpRequest).execute()
                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        // Rate limited — HealthTracker handles this at router level
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

                while (!source.exhausted() && finishReason == null) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") { finishReason = FinishReason.STOP; break }

                    try {
                        val jsonObj = json.parseToJsonElement(data).jsonObject
                        val candidates = jsonObj["candidates"]?.jsonArray
                        if (candidates != null && candidates.isNotEmpty()) {
                            val candidate = candidates[0].jsonObject
                            val content = candidate["content"]?.jsonObject
                            val parts = content?.get("parts")?.jsonArray
                            if (parts != null && parts.isNotEmpty()) {
                                val text = parts[0].jsonObject["text"]?.jsonPrimitive?.content
                                if (!text.isNullOrBlank()) {
                                    trySend(Token(text = text))
                                }
                            }
                            val finish = candidate["finishReason"]?.jsonPrimitive?.content
                            if (finish != null && finish != "" && finish != "null") {
                                finishReason = when (finish) {
                                    "STOP" -> FinishReason.STOP
                                    "MAX_TOKENS" -> FinishReason.LENGTH
                                    "SAFETY" -> FinishReason.ERROR
                                    "RECITATION" -> FinishReason.ERROR
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

    private fun buildGeminiRequest(request: CompletionRequest): String {
        return buildString {
            append("""{
                |"contents": [
                |  {
                |    "role": "user",
                |    "parts": [
                |      {"text": ${jsonEncode("${request.system}\n\n${request.user}")}}
                |    ]
                |  }
                |],
                |"generationConfig": {
                |  "temperature": ${request.temperature},
                |  "maxOutputTokens": ${request.maxTokens}
                |}
                |}""".trimMargin())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private fun jsonEncode(s: String): String =
            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    }
}
