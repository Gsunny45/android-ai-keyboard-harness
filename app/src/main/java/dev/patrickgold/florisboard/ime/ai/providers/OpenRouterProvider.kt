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
 * OpenRouter provider — OpenAI-compatible multi-model gateway.
 *
 * POST https://openrouter.ai/api/v1/chat/completions
 * Standard OpenAI SSE format. Bearer auth.
 *
 * Requires two extra headers vs plain OpenAI:
 *   HTTP-Referer: app identifier (avoids rate-limit reduction for anonymous calls)
 *   X-Title: human-readable app name (shows in OpenRouter dashboard)
 *
 * Free tier (2026):
 *   - Models with ":free" suffix = no cost, ~50 req/day without credits,
 *     ~1000 req/day after a one-time $10 credit purchase.
 *   - 20 RPM on free models.
 *
 * Default model: meta-llama/llama-3.3-70b-instruct:free
 * Other good free options: google/gemma-3-27b-it:free, mistralai/mistral-7b-instruct:free
 *
 * Role: fallback — disabled by default, enable in settings when you want
 * access to model variety without separate API keys.
 */
class OpenRouterProvider(
    override val config: ProviderConfig,
    private val apiKey: String,
) : Provider {

    override val id: String = "openrouter"
    override val displayName: String = "OpenRouter"

    private val modelName = config.model ?: "meta-llama/llama-3.3-70b-instruct:free"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
        val body = buildRequest(request)

        val httpRequest = Request.Builder()
            .url(config.url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/Gsunny45/android-ai-keyboard-harness")
            .header("X-Title", "agentA-Z Keyboard")
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
                            if (!content.isNullOrBlank()) trySend(Token(text = content))

                            val finish = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.content
                            if (!finish.isNullOrEmpty() && finish != "null") {
                                finishReason = when (finish) {
                                    "stop"           -> FinishReason.STOP
                                    "length"         -> FinishReason.LENGTH
                                    "content_filter" -> FinishReason.ERROR
                                    else             -> FinishReason.STOP
                                }
                            }
                        }
                    } catch (_: Exception) { /* skip malformed SSE */ }
                }

                trySend(Token("", finishReason ?: FinishReason.STOP))
            } catch (e: Exception) {
                trySend(Token("", FinishReason.ERROR))
            } finally {
                close()
            }
        }
    }

    private fun buildRequest(request: CompletionRequest): String = buildString {
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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private fun jsonEncode(s: String): String =
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<String>(), s
            )
    }
}
