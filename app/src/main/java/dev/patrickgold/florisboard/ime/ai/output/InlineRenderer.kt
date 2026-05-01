package dev.patrickgold.florisboard.ime.ai.output

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.ai.providers.FinishReason
import dev.patrickgold.florisboard.ime.ai.providers.Token
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders AI output directly into the focused EditText via InputConnection.
 *
 * ── Streaming ──
 *   Collects tokens from the provider Flow and commits them incrementally
 *   via [InputConnection.commitText]. The text appears character-by-character
 *   in the EditText as tokens arrive.
 *
 * ── Cancel ──
 *   During streaming, any key event (including backspace) sets a cancel flag.
 *   The stream stops and whatever text has been accumulated stays in the
 *   EditText (partial output is preserved). No rollback.
 *
 * ── /fix mode ──
 *   If the trigger's output_mode implies fix behavior, the renderer replaces
 *   the currently selected text (from InputConnection.getSelectedText)
 *   with the streamed output, using beginBatchEdit/endBatchEdit for an
 *   atomic replacement.
 *
 * ── Thread safety ──
 *   All InputConnection calls must happen on the IME thread. The renderer
 *   accepts a [CoroutineScope] (typically the IME's main scope) and
 *   launches collection there.
 */
class InlineRenderer(
    private val scope: CoroutineScope,
    /** Called when streaming is cancelled by user keypress. Receives token count committed so far. */
    private val onCancelled: ((tokensCommitted: Int) -> Unit)? = null,
) {
    // ── State ────────────────────────────────────────────────────────────

    @Volatile
    private var currentJob: Job? = null

    private val cancelFlag = AtomicBoolean(false)

    /** True while streaming is active. */
    @Volatile
    var isStreaming: Boolean = false
        private set

    /** Tracks how many tokens were committed during current stream. */
    @Volatile
    private var tokensCommitted: Int = 0

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Stream tokens into the given [inputConnection].
     *
     * @param inputConnection The active InputConnection from InputMethodService.
     * @param tokenFlow The cold Flow<Token> from the provider.
     * @param fixMode If true, replace selection instead of appending.
     * @param onComplete Callback with the final committed text (or null if cancelled).
     * @param onError Callback with error message if the stream fails.
     */
    fun stream(
        inputConnection: InputConnection,
        tokenFlow: Flow<Token>,
        fixMode: Boolean = false,
        onComplete: ((String?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        cancelExisting()

        isStreaming = true
        cancelFlag.set(false)
        tokensCommitted = 0

        currentJob = scope.launch {
            val textBuilder = StringBuilder()
            var errored = false

            try {
                if (fixMode) {
                    inputConnection.beginBatchEdit()
                    // Delete selected text before inserting corrected version
                    inputConnection.commitText("", 1)
                } else {
                    inputConnection.beginBatchEdit()
                }

                tokenFlow.collect { token ->
                    if (cancelFlag.get()) {
                        // Cancel requested — stop collecting, keep partial text
                        return@collect
                    }

                    when (token.finishReason) {
                        FinishReason.ERROR,
                        FinishReason.TIMEOUT -> {
                            errored = true
                            onError?.invoke("Stream terminated with ${token.finishReason}")
                            return@collect
                        }
                        else -> {
                            // Append token text
                            textBuilder.append(token.text)
                            inputConnection.commitText(token.text, 1)
                            tokensCommitted++
                        }
                    }
                }
            } catch (e: Exception) {
                errored = true
                onError?.invoke(e.message ?: "Stream error")
            } finally {
                inputConnection.endBatchEdit()
                isStreaming = false
                currentJob = null

                if (!errored && !cancelFlag.get()) {
                    onComplete?.invoke(textBuilder.toString())
                } else if (cancelFlag.get() && textBuilder.isNotEmpty()) {
                    // Even if cancelled, report partial text
                    onComplete?.invoke(textBuilder.toString())
                } else {
                    onComplete?.invoke(null)
                }
            }
        }
    }

    /**
     * Called by the IME when a key is pressed during streaming.
     * Returns true if the key was consumed (i.e., streaming was cancelled).
     */
    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (!isStreaming) return false
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return false

        // Any key press cancels the stream — including backspace,
        // letters, numbers, space, enter.
        cancelFlag.set(true)
        val committed = tokensCommitted
        // Cancel the coroutine job so the finally block runs immediately,
        // which calls endBatchEdit() and prevents InputConnection from
        // being stuck in batch edit mode while the provider continues streaming.
        currentJob?.cancel()
        currentJob = null
        isStreaming = false
        // Notify caller so they can show feedback (toast, haptic, etc.)
        onCancelled?.invoke(committed)
        return true // consumed: don't echo the key
    }

    /** Cancel the current stream if active. */
    fun cancel() {
        cancelFlag.set(true)
        cancelExisting()
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun cancelExisting() {
        currentJob?.cancel()
        currentJob = null
        isStreaming = false
    }
}
