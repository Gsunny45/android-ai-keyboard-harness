package dev.patrickgold.florisboard.ime.ai.providers

import android.util.Log
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Provider for local llama.cpp server at 127.0.0.1:8080.
 *
 * PATH 1 (implemented): HTTP(S) client to a llama-server instance the user
 *   runs externally (e.g. in Termux). Uses the OpenAI-compatible chat
 *   completions API with SSE streaming.
 *
 * PATH 2 (stubbed): JNI-based direct inference via a bundled .so loaded
 *   from jniLibs/. See [NativeBackend].
 *
 * API format:
 *   POST /v1/chat/completions
 *   Request: {"messages":[...], "stream":true}
 *   Response: text/event-stream with "data: {...}" lines
 */
class LlamaCppLocal(
    override val config: ProviderConfig,
) : Provider {

    override val id: String = "local"
    override val displayName: String = "Local (llama.cpp)"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
        val modelName = config.model ?: "gemma-3n-e2b"
        val requestBody = buildLlamaRequest(request, modelName)

        val httpRequest = Request.Builder()
            .url(config.url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .build()

        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(httpRequest).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "HTTP ${response.code}"
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

                // SSE line-by-line parsing
                while (!source.exhausted() && finishReason == null) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            finishReason = FinishReason.STOP
                            break
                        }
                        try {
                            val jsonObj = json.parseToJsonElement(data).jsonObject
                            val choices = jsonObj["choices"]?.jsonArray
                            if (choices != null && choices.isNotEmpty()) {
                                val delta = choices[0].jsonObject["delta"]?.jsonObject
                                val content = delta?.get("content")?.jsonPrimitive?.content
                                if (!content.isNullOrBlank()) {
                                    trySend(Token(text = content))
                                }
                                // Check finish reason
                                val finish = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.content
                                if (finish != null && finish != "null" && finish != "") {
                                    finishReason = when (finish) {
                                        "stop" -> FinishReason.STOP
                                        "length" -> FinishReason.LENGTH
                                        else -> FinishReason.STOP
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Skip malformed SSE lines
                        }
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

    private fun buildLlamaRequest(request: CompletionRequest, model: String): String {
        return buildString {
            append("""{
                |"model": "$model",
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
        private const val TAG = "LlamaCppLocal"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun jsonEncode(s: String): String {
            return kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
        }

        /**
         * Quick connectivity test: POST a 5-token prompt and measure latency.
         *
         * @param url The full provider URL (e.g. http://127.0.0.1:8080/v1/chat/completions)
         * @param model The model name to request
         * @param timeoutMs Connect/read timeout for the test
         * @return Pair(latencyMs, true) on success, Pair(elapsed, false) on failure
         */
        suspend fun testConnection(
            url: String,
            model: String = "gemma-3n-e2b",
            timeoutMs: Long = 10_000L,
        ): TestResult = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            try {
                // Tiny 5-token prompt — must return actual tokens, not empty
                val body = buildString {
                    append("""{
                        |"model": "$model",
                        |"messages": [{"role": "user", "content": "Hi"}],
                        |"stream": false,
                        |"max_tokens": 5,
                        |"temperature": 0.0
                        |}""".trimMargin())
                }

                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - start

                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext TestResult(elapsed, false, err)
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext TestResult(elapsed, false, "Empty response")
                }

                Log.d(TAG, "Test connection OK: ${elapsed}ms, body=${responseBody.take(120)}")
                return@withContext TestResult(elapsed, true)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                return@withContext TestResult(elapsed, false, e.message ?: "Connection failed")
            }
        }

        /**
         * Check if the server is reachable by calling GET /health.
         */
        suspend fun checkHealth(
            baseUrl: String = "http://127.0.0.1:8080",
            timeoutMs: Long = 3_000L,
        ): HealthResult = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            try {
                val request = Request.Builder()
                    .url("$baseUrl/health")
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - start
                val ok = response.isSuccessful

                if (ok) {
                    HealthResult(elapsed, true, null)
                } else {
                    HealthResult(elapsed, false, "HTTP ${response.code}")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                HealthResult(elapsed, false, e.message ?: "Unreachable")
            }
        }

        /**
         * Fetch tokens/sec from /metrics (Prometheus format).
         * Parses the first `llama_request_tokens_per_second` value.
         */
        suspend fun fetchTokensPerSec(
            baseUrl: String = "http://127.0.0.1:8080",
            timeoutMs: Long = 3_000L,
        ): Float? = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            try {
                val request = Request.Builder()
                    .url("$baseUrl/metrics")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val pattern = Regex("""llama_request_tokens_per_second\s+([\d.]+)""")
                val match = pattern.find(body)
                match?.groupValues?.get(1)?.toFloatOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Result of a connection test.
 */
data class TestResult(
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null,
)

/**
 * Result of a health check.
 */
data class HealthResult(
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null,
)

// ── PATH 2: JNI native backend stub ──────────────────────────────────────

/**
 * Stub for PATH 2 (bundled .so inference).
 *
 * When launched from jniLibs/arm64-v8a/libllama.so, this object will
 * provide direct model loading and inference without an external server.
 *
 * Implementation is a no-op until the native .so is bundled — see
 * LOCAL_LLAMA.md for details.
 */
object NativeBackend {
    private const val TAG = "NativeBackend"

    /** Has the native library been loaded? */
    var isLoaded: Boolean = false
        private set

    /** Model path on disk (set before [loadInference]). */
    var modelPath: String = ""
        private set

    /**
     * PATH 2: Load the bundled libllama.so from jniLibs/.
     * Stubbed — will throw UnsupportedOperationException until the .so
     * is bundled.
     */
    fun loadLibrary() {
        Log.i(TAG, "PATH 2 stub: native library loading not yet implemented")
        // TODO: System.loadLibrary("llama")
        throw UnsupportedOperationException(
            "PATH 2 (bundled .so) is not available in v0.1. " +
                "Install llama-server in Termux (PATH 1) instead. " +
                "See docs/LOCAL_LLAMA.md"
        )
    }

    /**
     * PATH 2: Load a GGUF model into memory.
     * @param path Absolute path to the .gguf file on device storage.
     */
    fun loadModel(path: String) {
        modelPath = path
        Log.i(TAG, "PATH 2 stub: loadModel($path) — not implemented in v0.1")
    }

    /**
     * PATH 2: Run a single inference pass.
     * @return Generated text, or null on failure.
     */
    suspend fun infer(system: String, user: String, maxTokens: Int): String? {
        Log.i(TAG, "PATH 2 stub: infer() — not implemented in v0.1")
        return null
    }

    /** Release model resources. */
    fun unload() {
        isLoaded = false
        modelPath = ""
        Log.i(TAG, "PATH 2 stub: unload() — not implemented in v0.1")
    }
}
