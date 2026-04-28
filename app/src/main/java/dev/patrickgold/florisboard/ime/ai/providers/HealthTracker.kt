package dev.patrickgold.florisboard.ime.ai.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * Rolling health state tracker per provider.
 *
 * Health rules:
 *   - After 2 consecutive connection failures, unreachable = true
 *   - unreachable auto-clears after 60s
 *   - rateLimited = true on HTTP 429, applies Retry-After header value
 *   - rateLimit clears after Retry-After seconds (default 30)
 *   - avgLatencyMs is an exponential moving average (alpha = 0.3)
 */
class HealthTracker {

    data class ProviderHealth(
        val unreachable: Boolean = false,
        val rateLimited: Boolean = false,
        val retryAfterMs: Long = 0L,
        val avgLatencyMs: Long = 0L,
        val lastError: String? = null,
        val lastFailureTime: Long = 0L,
        val consecutiveFailures: Int = 0,
        val totalCalls: Int = 0,
        val successCalls: Int = 0,
    )

    private val health = ConcurrentHashMap<String, ProviderHealth>()

    // ── Public API ───────────────────────────────────────────────────────

    /** Record a successful provider call. */
    fun recordSuccess(providerId: String, latencyMs: Long) {
        val current = getHealth(providerId)
        val ema = if (current.totalCalls == 0) latencyMs
        else (current.avgLatencyMs * (1.0 - EMA_ALPHA) + latencyMs * EMA_ALPHA).toLong()

        health[providerId] = current.copy(
            unreachable = false,
            rateLimited = false,
            avgLatencyMs = ema,
            lastError = null,
            consecutiveFailures = 0,
            totalCalls = current.totalCalls + 1,
            successCalls = current.successCalls + 1,
        )
    }

    /** Record a failure (connection error, timeout, 5xx). */
    fun recordFailure(providerId: String, error: String) {
        val current = getHealth(providerId)
        val newFailures = current.consecutiveFailures + 1

        health[providerId] = current.copy(
            unreachable = newFailures >= 2,
            lastError = error,
            lastFailureTime = now(),
            consecutiveFailures = newFailures,
            totalCalls = current.totalCalls + 1,
        )
    }

    /** Record HTTP 429 rate limit. */
    fun recordRateLimit(providerId: String, retryAfterSec: Int = 30) {
        val current = getHealth(providerId)
        health[providerId] = current.copy(
            rateLimited = true,
            retryAfterMs = now() + (retryAfterSec * 1000L),
            lastError = "HTTP 429 Rate Limited (retry-after: ${retryAfterSec}s)",
            lastFailureTime = now(),
            totalCalls = current.totalCalls + 1,
        )
    }

    /** Returns true if this provider is available for routing. */
    fun isHealthy(providerId: String): Boolean {
        val h = getHealth(providerId)
        if (!h.unreachable && !h.rateLimited) return true
        // Auto-clear stale states
        val elapsed = now() - h.lastFailureTime
        if (h.unreachable && elapsed >= UNREACHABLE_CLEAR_MS) {
            clearUnreachable(providerId)
            return true
        }
        if (h.rateLimited && elapsed >= h.retryAfterMs - h.lastFailureTime) {
            clearRateLimit(providerId)
            return true
        }
        return false
    }

    /** Returns current health snapshot (always non-null). */
    fun getHealth(providerId: String): ProviderHealth {
        return health[providerId] ?: ProviderHealth()
    }

    /** Health map for routing context. */
    fun allHealth(): Map<String, ProviderHealth> = health.toMap()

    // ── Internal ─────────────────────────────────────────────────────────

    private fun clearUnreachable(providerId: String) {
        health.computeIfPresent(providerId) { _, v -> v.copy(unreachable = false, consecutiveFailures = 0) }
    }

    private fun clearRateLimit(providerId: String) {
        health.computeIfPresent(providerId) { _, v -> v.copy(rateLimited = false, retryAfterMs = 0L) }
    }

    internal fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val EMA_ALPHA = 0.3
        private const val UNREACHABLE_CLEAR_MS = 60_000L
    }
}
