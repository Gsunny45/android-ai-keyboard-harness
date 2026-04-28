package dev.patrickgold.florisboard.ime.ai.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A single streamed token from an AI provider.
 */
data class Token(
    val text: String,
    val finishReason: FinishReason? = null,
)

enum class FinishReason {
    /** Model finished naturally. */
    STOP,
    /** Response hit max_tokens limit. */
    LENGTH,
    /** Provider returned an error. */
    ERROR,
    /** Request timed out. */
    TIMEOUT,
}

/**
 * The resolved prompt after CTE variable substitution and persona merge.
 */
data class ResolvedPrompt(
    val system: String,
    val user: String,
    val temperature: Float,
    val maxTokens: Int,
    val pipeline: String,
    val extractPattern: String? = null,
    val branches: Int = 1,
    val preferredProvider: String? = null,
    val fallbackProvider: String? = null,
    val budget: String = "balanced",
)

/**
 * Provider-level request (after routing is decided).
 */
data class CompletionRequest(
    val system: String,
    val user: String,
    val temperature: Float,
    val maxTokens: Int,
    val timeoutMs: Long,
    val apiKey: String? = null,
)

/**
 * Result metadata logged after a provider call completes.
 */
data class CompletionResult(
    val providerId: String,
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null,
)

/**
 * Interface for all AI provider implementations.
 *
 * Each provider:
 *   - Has a unique [id] matching the key in triggers.json "providers" section
 *   - Reads its config (url, model, keyRef, timeoutMs) from [config]
 *   - Returns a [Flow] of [Token] for streaming output
 */
interface Provider {
    val id: String
    val displayName: String
    val config: ProviderConfig

    /** Stream a completion. Returns immediately with a cold Flow. */
    fun complete(request: CompletionRequest): Flow<Token>
}

/**
 * Provider configuration as defined in triggers.json "providers" section.
 * Not all fields apply to every provider (e.g. local has no keyRef).
 */
@Serializable
data class ProviderConfig(
    val url: String,
    val model: String? = null,
    val keyRef: String? = null,
    val priority: Int = 10,
    val maxTokens: Int = 2048,
    val timeoutMs: Long = 30_000L,
)
