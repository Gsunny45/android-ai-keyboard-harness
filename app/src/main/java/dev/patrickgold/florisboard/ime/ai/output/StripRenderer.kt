package dev.patrickgold.florisboard.ime.ai.output

import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.ai.providers.CompletionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Renders AI output as up to 3 candidate suggestions on FlorisBoard's
 * suggestion strip (the bar above the keyboard showing word predictions).
 *
 * Uses the ToT (Tree-of-Thought) pipeline: each branch produces one
 * candidate. The strip shows all branches simultaneously. Single-shot
 * (CoT) pipelines show one candidate.
 *
 * ── Interaction ──
 *   - Tap a candidate → inserts the full text via InputConnection.commitText
 *   - Long-press a candidate → inserts the text AND saves it as a snippet
 *     in a local snippet store (persisted for reuse)
 *
 * ── Snippet storage ──
 *   Long-press saves the candidate text to a flat file in the app's
 *   internal storage (snippets/). Snapshot: no indexing required.
 */
class StripRenderer {

    // ── Types ────────────────────────────────────────────────────────────

    data class Candidate(
        val text: String,
        val branchIndex: Int = 0,
        val confidence: Float = 0.5f,
        val pipeline: String = "cot",
    )

    // ── State ────────────────────────────────────────────────────────────

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Present results on the suggestion strip.
     *
     * @param results List of completion results (one per ToT branch).
     * @param pipeline "cot" or "tot" — determines how many candidates to show.
     */
    fun showResults(results: List<CompletionResult>, pipeline: String) {
        val candidates = results.mapIndexed { index, result ->
            Candidate(
                text = result.text.trim(),
                branchIndex = index,
                confidence = 1.0f - (index.toFloat() / results.size.coerceAtLeast(1)),
                pipeline = pipeline,
            )
        }.filter { it.text.isNotBlank() }
            .take(MAX_CANDIDATES) // max 3

        _candidates.value = candidates
    }

    /** Clear the suggestion strip. */
    fun clear() {
        _candidates.value = emptyList()
    }

    /**
     * Insert a candidate via [inputConnection].
     * Called when the user taps a suggestion.
     */
    fun insertCandidate(candidate: Candidate, inputConnection: InputConnection) {
        inputConnection.beginBatchEdit()
        inputConnection.commitText(candidate.text, 1)
        inputConnection.endBatchEdit()
        clear()
    }

    /**
     * Insert a candidate AND save it as a reusable snippet.
     * Called when the user long-presses a suggestion.
     */
    fun insertAndSaveSnippet(candidate: Candidate, inputConnection: InputConnection, snippetStore: SnippetStore) {
        insertCandidate(candidate, inputConnection)
        snippetStore.save(candidate.text)
    }

    // ── Snippet store ────────────────────────────────────────────────────

    /**
     * Minimal snippet persistence. Saves text snippets to a flat file
     * in the app's internal storage, one per line.
     */
    class SnippetStore(private val snippetsDir: java.io.File) {

        private val snippetsFile = java.io.File(snippetsDir, "saved_snippets.txt")

        init {
            snippetsDir.mkdirs()
        }

        fun save(text: String) {
            try {
                snippetsFile.appendText(text.trim() + "\n")
            } catch (_: Exception) {
                // Silently fail — snippets are non-critical
            }
        }

        fun loadAll(): List<String> {
            return try {
                snippetsFile.readLines().filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    companion object {
        private const val MAX_CANDIDATES = 3
    }
}
