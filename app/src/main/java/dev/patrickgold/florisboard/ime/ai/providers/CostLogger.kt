package dev.patrickgold.florisboard.ime.ai.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local-only cost telemetry for AI provider calls.
 *
 * Stores per-call entries in memory. The last 7 days of data are exposed
 * for the Cost settings screen. Data is never sent anywhere.
 *
 * Token estimation uses a simple heuristic:
 *   ~4 characters per token for English text.
 */
class CostLogger {

    data class CostEntry(
        val providerId: String,
        val timestamp: Long,
        val triggerId: String? = null,
        val inputChars: Int = 0,
        val outputChars: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val latencyMs: Long = 0L,
        val success: Boolean = true,
        val error: String? = null,
    )

    data class CostSummary(
        val totalCalls: Int = 0,
        val totalInputTokens: Int = 0,
        val totalOutputTokens: Int = 0,
        val totalLatencyMs: Long = 0L,
        val successCalls: Int = 0,
        val failedCalls: Int = 0,
    )

    private val _entries = MutableStateFlow<List<CostEntry>>(emptyList())

    /** Observable stream of all entries (newest first). */
    val entries: StateFlow<List<CostEntry>> = _entries

    // ── Logging ──────────────────────────────────────────────────────────

    /** Log a completed provider call. */
    fun log(providerId: String, inputText: String, outputText: String, latencyMs: Long, success: Boolean, error: String? = null, triggerId: String? = null) {
        val entry = CostEntry(
            providerId = providerId,
            timestamp = now(),
            triggerId = triggerId,
            inputChars = inputText.length,
            outputChars = outputText.length,
            inputTokens = estimateTokens(inputText),
            outputTokens = estimateTokens(outputText),
            latencyMs = latencyMs,
            success = success,
            error = error,
        )
        _entries.value = listOf(entry) + _entries.value
    }

    // ── Aggregation ──────────────────────────────────────────────────────

    /** Summary per provider for the last 7 days. */
    fun last7DaysByProvider(): Map<String, CostSummary> {
        val cutoff = now() - DAY_MS * 7
        val recent = _entries.value.filter { it.timestamp >= cutoff }
        return recent.groupBy { it.providerId }.mapValues { (_, entries) ->
            CostSummary(
                totalCalls = entries.size,
                totalInputTokens = entries.sumOf { it.inputTokens },
                totalOutputTokens = entries.sumOf { it.outputTokens },
                totalLatencyMs = entries.sumOf { it.latencyMs },
                successCalls = entries.count { it.success },
                failedCalls = entries.count { !it.success },
            )
        }
    }

    /** All entries for the last 7 days. */
    fun last7DaysEntries(): List<CostEntry> {
        val cutoff = now() - DAY_MS * 7
        return _entries.value.filter { it.timestamp >= cutoff }
    }

    /** Daily aggregates for the last 7 days (for the Cost bar chart). */
    fun last7DaysDaily(): List<DailyCost> {
        val cutoff = now() - DAY_MS * 7
        val byDay = _entries.value
            .filter { it.timestamp >= cutoff }
            .groupBy { entry ->
                // Group by date (not timestamp)
                val dayMs = (entry.timestamp / DAY_MS) * DAY_MS
                dayMs
            }
        return byDay.map { (dayMs, entries) ->
            DailyCost(
                dateMs = dayMs,
                providerBreakdown = entries.groupBy { it.providerId }.mapValues { (_, es) ->
                    CostSummary(
                        totalCalls = es.size,
                        totalInputTokens = es.sumOf { it.inputTokens },
                        totalOutputTokens = es.sumOf { it.outputTokens },
                        totalLatencyMs = es.sumOf { it.latencyMs },
                        successCalls = es.count { it.success },
                        failedCalls = es.count { !it.success },
                    )
                }
            )
        }.sortedBy { it.dateMs }
    }

    /** Clear all entries. */
    fun clear() {
        _entries.value = emptyList()
    }

    // ── Token estimation ─────────────────────────────────────────────────

    companion object {
        /**
         * Simple whitespace + punctuation heuristic for token estimation.
         * Approximately 4 characters per token for English.
         * More accurate than char/4 for code/markdown.
         */
        fun estimateTokens(text: String): Int {
            if (text.isBlank()) return 0
            // Count whitespace-separated "words" plus special chars
            var tokens = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> {
                        tokens++
                        i++
                    }
                    c in ".,!?;:\"'()-[]{}" -> {
                        tokens++
                        i++
                    }
                    else -> {
                        // Count ~4 char runs as tokens
                        tokens++
                        i += 4
                    }
                }
            }
            return maxOf(1, tokens)
        }

        private const val DAY_MS = 86_400_000L
    }

    private fun now(): Long = System.currentTimeMillis()
}

data class DailyCost(
    val dateMs: Long,
    val providerBreakdown: Map<String, CostLogger.CostSummary>,
)
