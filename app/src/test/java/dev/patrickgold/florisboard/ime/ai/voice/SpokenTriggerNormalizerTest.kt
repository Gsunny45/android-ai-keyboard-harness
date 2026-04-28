package dev.patrickgold.florisboard.ime.ai.voice

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SpokenTriggerNormalizer].
 *
 * Covers all 20 entries in the spoken-form → canonical-trigger mapping
 * table, plus prefix matching, case-insensitivity, edge cases, and
 * empty/blank transcripts.
 *
 * Test groups:
 *   1. All 20 exact spoken forms → canonical triggers
 *   2. Spoken-form prefix with remaining argument text
 *   3. No trigger in transcript (plain text pass-through)
 *   4. Case-insensitive matching
 *   5. Edge cases — multi-word, word-boundary, no-false-positive
 *   6. Empty / blank transcript
 */
class SpokenTriggerNormalizerTest {

    private lateinit var normalizer: SpokenTriggerNormalizer

    @Before
    fun setup() {
        normalizer = SpokenTriggerNormalizer()
    }

    // ── Test 1: All 20 exact spoken forms ────────────────────────────────

    @Test
    fun `all 20 exact spoken forms map to canonical triggers`() {
        // This test exhaustively covers every entry in the 20-row mapping table.
        // Any addition/removal from the normalizer must update this list.
        val all20: Map<String, String> = mapOf(
            // Global triggers (style / tone)
            "slash formal"                      to "/formal",
            "slash casual"                      to "/casual",
            "slash code"                        to "/code",
            "slash expand"                      to "/expand",
            "slash fix"                         to "/fix",

            // Pipeline triggers
            "chain of thought"                  to "<<cot>>",
            "tree of thought"                   to "<<tot>>",

            // Obsidian triggers
            "slash doc"                         to "/doc",
            "slash summarize"                   to "/summarize",
            "slash link"                        to "/link",
            "slash daily"                       to "/daily",
            "slash atomic"                      to "/atomic",
            "slash moc"                         to "/moc",

            // WhatsApp / messaging triggers
            "slash reply"                       to "/reply",
            "slash formalize"                   to "/formalize",
            "slash shorten"                     to "/shorten",
            "slash translate"                   to "/translate",

            // Gmail triggers
            "slash draft"                       to "/draft",

            // Meta triggers
            "slash skill"                       to "/skill",
            "slash meta"                        to "/meta",
        )

        // Verify we have exactly 20
        assertEquals("Must have exactly 20 entries", 20, all20.size)

        val actualMappings = normalizer.allMappings()
        assertEquals("Normalizer must have exactly 20 entries", 20, actualMappings.size)

        for ((spoken, expected) in all20) {
            val result = normalizer.normalize(spoken)
            assertEquals("Spoken: '$spoken'", expected, result.triggerId)
            assertTrue("Should have trigger flag for '$spoken'", result.hasTrigger)
            assertEquals("Args should be empty for exact match '$spoken'", "", result.args)
        }
    }

    // ── Test 2: Spoken-form prefix with remaining argument text ──────────

    @Test
    fun `spoken form with remaining args stripped as prefix`() {
        val testCases = mapOf(
            "slash formal please make this polite"        to Pair("/formal", "please make this polite"),
            "slash doc explain the layered architecture"  to Pair("/doc", "explain the layered architecture"),
            "slash fix there are typos in this text"      to Pair("/fix", "there are typos in this text"),
            "slash skill vault_aware_draft"               to Pair("/skill", "vault_aware_draft"),
            "chain of thought think about this problem"   to Pair("<<cot>>", "think about this problem"),
            "tree of thought explore all branches"        to Pair("<<tot>>", "explore all branches"),
            "slash summarize the current note content"    to Pair("/summarize", "the current note content"),
            "slash link cryptography concepts"            to Pair("/link", "cryptography concepts"),
            "slash daily today I worked on"               to Pair("/daily", "today I worked on"),
            "slash atomic one idea per note"              to Pair("/atomic", "one idea per note"),
            "slash moc create index for vault"            to Pair("/moc", "create index for vault"),
            "slash draft compose weekly status"           to Pair("/draft", "compose weekly status"),
            "slash reply thanks for the update"           to Pair("/reply", "thanks for the update"),
            "slash formalize this message"                to Pair("/formalize", "this message"),
            "slash shorten the explanation"               to Pair("/shorten", "the explanation"),
            "slash translate to spanish"                  to Pair("/translate", "to spanish"),
            "slash expand on that idea"                   to Pair("/expand", "on that idea"),
            "slash casual make it friendly"               to Pair("/casual", "make it friendly"),
            "slash code format the response"              to Pair("/code", "format the response"),
            "slash meta show all skills"                  to Pair("/meta", "show all skills"),
        )

        assertEquals("Must cover all 20 mappings with args", 20, testCases.size)

        for (entry in testCases) {
            val spoken = entry.key
            val expectedTrigger = entry.value.first
            val expectedArgs = entry.value.second
            val result = normalizer.normalize(spoken)
            assertEquals("Trigger for '$spoken'", expectedTrigger, result.triggerId)
            assertEquals("Args for '$spoken'", expectedArgs, result.args)
            assertTrue("Should have trigger flag", result.hasTrigger)
        }
    }

    // ── Test 3: No trigger in transcript — pass through ──────────────────

    @Test
    fun `no trigger detected passes transcript as args`() {
        val plainTexts = listOf(
            "this is just a note",
            "remind me to buy groceries",
            "the quick brown fox",
            "123456",
            "what is the meaning of life",
            "I need to remember this for later",
            "call mom about dinner",
        )

        for (text in plainTexts) {
            val result = normalizer.normalize(text)
            assertNull("Should not detect trigger in '$text'", result.triggerId)
            assertFalse("hasTrigger should be false for '$text'", result.hasTrigger)
            assertEquals("Args should be the original text for '$text'", text, result.args)
            assertEquals("originalTranscript should match for '$text'", text, result.originalTranscript)
        }
    }

    // ── Test 4: Case-insensitive matching ────────────────────────────────

    @Test
    fun `case insensitive matching`() {
        val testCases = mapOf(
            "SLASH FORMAL"                    to "/formal",
            "Slash Casual"                    to "/casual",
            "SLASH CODE"                      to "/code",
            "Slash Fix"                       to "/fix",
            "SLASH DOC explain this"          to "/doc",
            "CHAIN OF THOUGHT"                to "<<cot>>",
            "Tree Of Thought"                 to "<<tot>>",
            "Slash Atomic"                    to "/atomic",
            "SLASH MOC"                       to "/moc",
            "Slash Summarize the note"        to "/summarize",
            "SLASH REPLY confirm"             to "/reply",
            "Slash Draft"                     to "/draft",
            "SLASH SKILL vault_aware_draft"   to "/skill",
            "Slash Meta display"              to "/meta",
            "SLASH TRANSLATE to german"       to "/translate",
            "Slash Shorten"                   to "/shorten",
            "SLASH FORMALIZE"                 to "/formalize",
            "Slash Expand that"               to "/expand",
            "SLASH LINK cryptography"         to "/link",
            "Slash Daily notes for today"     to "/daily",
        )

        assertEquals("Must cover all 20 mappings case-insensitively", 20, testCases.size)

        for ((spoken, expectedTrigger) in testCases) {
            val result = normalizer.normalize(spoken)
            assertEquals("Case-insensitive: '$spoken'", expectedTrigger, result.triggerId)
        }
    }

    // ── Test 5: Edge cases ──────────────────────────────────────────────

    @Test
    fun `edge cases — multi-word, word boundary, no false positive`() {
        // Pipeline triggers use full natural-language phrases
        assertEquals(
            "<<cot>>",
            normalizer.normalize("chain of thought").triggerId,
        )
        assertEquals(
            "<<tot>>",
            normalizer.normalize("tree of thought").triggerId,
        )

        // "moc" inside "slash moc" works
        assertEquals("/moc", normalizer.normalize("slash moc").triggerId)

        // Transcript contains trigger word as part of longer text — no match
        val noTrigger = normalizer.normalize("I need to fix this car myself")
        assertNull(
            "Should not match when trigger is embedded in sentence",
            noTrigger.triggerId,
        )

        // Leading/trailing whitespace tolerance
        assertEquals(
            "/formal",
            normalizer.normalize("  slash formal  ").triggerId,
        )

        // Partial word that happens to start with a trigger — no match
        val fixation = normalizer.normalize("fixation on details")
        assertNull(
            "Should not match 'fix' when word continues: 'fixation'",
            fixation.triggerId,
        )

        val coding = normalizer.normalize("coding is fun")
        assertNull(
            "Should not match 'code' when word continues: 'coding'",
            coding.triggerId,
        )
    }

    // ── Test 6: Empty and blank transcript ───────────────────────────────

    @Test
    fun `empty and blank transcript`() {
        val emptyResult = normalizer.normalize("")
        assertNull("Empty transcript", emptyResult.triggerId)
        assertEquals("Empty args", "", emptyResult.args)
        assertEquals("Empty original", "", emptyResult.originalTranscript)
        assertFalse("hasTrigger should be false", emptyResult.hasTrigger)

        val blankResult = normalizer.normalize("   ")
        assertNull("Blank transcript", blankResult.triggerId)
        assertEquals("Blank args", "", blankResult.args)
        assertFalse("hasTrigger should be false", blankResult.hasTrigger)

        val whitespaceResult = normalizer.normalize("\t\n  ")
        assertNull("Whitespace-only transcript", whitespaceResult.triggerId)
        assertFalse("hasTrigger should be false", whitespaceResult.hasTrigger)
    }

    // ── Test 7: allMappings returns exactly 20 ───────────────────────────

    @Test
    fun `allMappings returns exactly 20 entries`() {
        val mappings = normalizer.allMappings()
        assertEquals("allMappings size must be 20", 20, mappings.size)

        // Verify every entry has a non-empty spoken form and canonical trigger
        mappings.forEach { (spoken, canonical) ->
            assertTrue("Spoken form should not be blank: '$spoken'", spoken.isNotBlank())
            assertTrue("Canonical trigger should not be blank: '$canonical'", canonical.isNotBlank())
        }
    }

    // ── Test 8: lookup returns correct mapping ───────────────────────────

    @Test
    fun `lookup returns correct mapping for registered forms`() {
        assertEquals("/formal", normalizer.lookup("slash formal"))
        assertEquals("/fix", normalizer.lookup("slash fix"))
        assertEquals("<<cot>>", normalizer.lookup("chain of thought"))

        // Unregistered form returns null
        assertNull(normalizer.lookup("nonexistent trigger"))
        assertNull(normalizer.lookup(""))
    }

    // ── Test 9: NormalizedResult data class properties ──────────────────

    @Test
    fun `NormalizedResult properties are correct`() {
        val result = normalizer.normalize("slash formal")
        assertTrue(result.hasTrigger)
        assertEquals("/formal", result.triggerId)
        assertEquals("", result.args)
        assertEquals("slash formal", result.originalTranscript)

        val plain = normalizer.normalize("hello world")
        assertFalse(plain.hasTrigger)
        assertNull(plain.triggerId)
        assertEquals("hello world", plain.args)
        assertEquals("hello world", plain.originalTranscript)
    }
}
