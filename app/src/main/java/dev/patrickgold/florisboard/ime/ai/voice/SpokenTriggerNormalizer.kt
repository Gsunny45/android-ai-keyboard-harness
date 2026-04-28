package dev.patrickgold.florisboard.ime.ai.voice

/**
 * Normalizes spoken trigger phrases into canonical trigger tokens.
 *
 * Bridges the gap between what a user says and what the trigger parser
 * expects. The mapping table contains exactly 20 entries covering all
 * registered spoken triggers.
 *
 * Matching strategy:
 *   1. Try exact match against the spoken-form table (case-insensitive)
 *   2. Try prefix match on the transcript start (longest match wins)
 *   3. Fall through with triggerId = null (no trigger — pass as plain text)
 */
class SpokenTriggerNormalizer {

    /**
     * All 20 spoken-form → canonical-trigger mappings.
     *
     * Forms are lowercase, space-separated. The normalizer lowercases the
     * transcript before matching.
     */
    private val exactMappings: Map<String, String> = mapOf(
        // ===== Global triggers (style / tone) =====
        "slash formal"                          to "/formal",
        "slash casual"                          to "/casual",
        "slash code"                            to "/code",
        "slash expand"                          to "/expand",
        "slash fix"                             to "/fix",

        // ===== Pipeline triggers =====
        "chain of thought"                      to "<<cot>>",
        "tree of thought"                       to "<<tot>>",

        // ===== Obsidian triggers =====
        "slash doc"                             to "/doc",
        "slash summarize"                       to "/summarize",
        "slash link"                            to "/link",
        "slash daily"                           to "/daily",
        "slash atomic"                          to "/atomic",
        "slash moc"                             to "/moc",

        // ===== WhatsApp / messaging triggers =====
        "slash reply"                           to "/reply",
        "slash formalize"                       to "/formalize",
        "slash shorten"                         to "/shorten",
        "slash translate"                       to "/translate",

        // ===== Gmail triggers =====
        "slash draft"                           to "/draft",

        // ===== Meta triggers =====
        "slash skill"                           to "/skill",
        "slash meta"                            to "/meta",
    )

    /**
     * Normalize a voice transcript.
     *
     * @return [NormalizedResult] containing the matched trigger (if any),
     *         remaining argument text, and the original transcript.
     */
    fun normalize(transcript: String): NormalizedResult {
        val cleaned = transcript.trim().lowercase()
        if (cleaned.isBlank()) return NormalizedResult(null, "", transcript)

        val original = transcript.trim()

        // Strategy 1: Exact match on the full transcript
        exactMappings[cleaned]?.let { trigger ->
            return NormalizedResult(trigger, "", transcript)
        }

        // Strategy 2: Longest-matching prefix
        val sorted = exactMappings.entries.sortedByDescending { it.key.length }

        for ((spoken, canonical) in sorted) {
            if (cleaned.startsWith("$spoken ") || cleaned == spoken) {
                val args = if (cleaned.length > spoken.length) {
                    // Preserve original casing — extract from original transcript
                    original.substring(spoken.length).trim()
                } else {
                    ""
                }
                return NormalizedResult(canonical, args, transcript)
            }
        }

        // No trigger detected — pass through as plain text (preserve original casing)
        return NormalizedResult(null, original, transcript)
    }

    /**
     * Returns the canonical trigger for a spoken form, or null.
     */
    fun lookup(spokenForm: String): String? {
        return exactMappings[spokenForm.trim().lowercase()]
    }

    /**
     * Returns all registered spoken forms (for Settings / Diagnostics).
     */
    fun allMappings(): Map<String, String> = exactMappings
}

/**
 * The result of normalizing a spoken transcript.
 *
 * @property triggerId          Canonical trigger string (e.g. "/fix"),
 *                              or null if no trigger was detected.
 * @property args               Remaining argument text after the trigger.
 * @property originalTranscript The raw transcript that was passed in.
 */
data class NormalizedResult(
    val triggerId: String?,
    val args: String,
    val originalTranscript: String,
) {
    /** True iff a trigger was detected in the transcript. */
    val hasTrigger: Boolean get() = triggerId != null
}
