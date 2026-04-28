package dev.patrickgold.florisboard.ime.ai.trigger

/**
 * Parses a normalized input string into a structured trigger command.
 *
 * Input formats:
 *   - `/triggerId arg1 arg2`      — standard trigger with arguments
 *   - `<<pipeline>> arg1 arg2`    — pipeline trigger (chain-of-thought etc.)
 *   - plain text                  — no trigger detected, pass through
 *
 * Used by [VoiceInputManager] to dispatch spoken commands after
 * [SpokenTriggerNormalizer] has canonicalised the transcript.
 */
class TriggerParser {

    /** Regex that matches a standard `/trigger` at the start of input. */
    private val slashTrigger = Regex("""^/([a-zA-Z_][a-zA-Z0-9_]*)(?:\s+(.*))?$""")

    /** Regex that matches a pipeline `<<name>>` at the start of input. */
    private val angleTrigger = Regex("""^<<([a-zA-Z_][a-zA-Z0-9_]*)>>(?:\s+(.*))?$""")

    /**
     * Parse [input] and return a [ParsedTrigger].
     *
     * The parser is strict: only a leading `/id` or `<<id>>` constitutes a
     * trigger. Everything else is treated as plain text.
     */
    fun parse(input: String): ParsedTrigger {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ParsedTrigger(null, emptyList(), input)

        slashTrigger.find(trimmed)?.let { match ->
            val id = match.groupValues[1]
            val args = parseArgs(match.groupValues.getOrElse(2) { "" })
            return ParsedTrigger(id, args, input)
        }

        angleTrigger.find(trimmed)?.let { match ->
            val id = "<<${match.groupValues[1]}>>"
            val args = parseArgs(match.groupValues.getOrElse(2) { "" })
            return ParsedTrigger(id, args, input)
        }

        return ParsedTrigger(null, listOf(trimmed), input)
    }

    /** Splits argument string on whitespace, respecting double-quoted groups. */
    private fun parseArgs(argString: String): List<String> {
        if (argString.isBlank()) return emptyList()
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        for (ch in argString.trim()) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) args.add(current.toString())
        return args
    }
}

/**
 * The result of parsing a trigger input.
 *
 * @property triggerId  The canonical trigger ID (e.g. "/fix", "<<cot>>"),
 *                       or null for plain-text pass-through.
 * @property args       Parsed argument tokens.
 * @property raw        The original input string.
 */
data class ParsedTrigger(
    val triggerId: String?,
    val args: List<String>,
    val raw: String,
) {
    val hasTrigger: Boolean get() = triggerId != null
}
