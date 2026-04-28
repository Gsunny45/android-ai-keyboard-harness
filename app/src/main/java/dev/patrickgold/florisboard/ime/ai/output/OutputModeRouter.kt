package dev.patrickgold.florisboard.ime.ai.output

import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.ai.providers.CompletionResult
import dev.patrickgold.florisboard.ime.ai.providers.FinishReason
import dev.patrickgold.florisboard.ime.ai.providers.Token
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Routes AI output to the appropriate renderer based on the trigger's
 * `output_mode` field: "inline", "strip", or "overlay".
 *
 * The router owns the decision of which renderer to use and handles
 * the common concern of collecting the full output before dispatching
 * to the strip/overlay renderers (which need the complete text).
 *
 * ── Mode selection logic ──
 *   "inline"  → InlineRenderer:  streams tokens directly to EditText
 *   "strip"   → StripRenderer:   collects all text, shows up to 3 candidates
 *   "overlay" → OverlayRenderer: collects all text, shows bottom-sheet
 *   undefined → defaults to "inline"
 *
 * ── Pipeline integration ──
 *   ToT pipelines always route to "strip" (each branch = 1 candidate).
 *   CoT pipelines use the trigger-defined output_mode.
 */
class OutputModeRouter(
    private val inlineRenderer: InlineRenderer,
    private val stripRenderer: StripRenderer,
    private val overlayRenderer: OverlayRenderer,
    private val scope: CoroutineScope,
) {

    /**
     * Render a streaming completion to the appropriate output target.
     *
     * @param tokenFlow The streaming token flow from the provider.
     * @param inputConnection The active InputConnection for text insertion.
     * @param outputMode The trigger's output_mode ("inline", "strip", "overlay").
     * @param pipeline The pipeline type ("cot" or "tot").
     * @param fixMode If true, replace selection instead of appending (inline only).
     * @param onComplete Callback with the final text (or null if cancelled).
     */
    fun render(
        tokenFlow: Flow<Token>,
        inputConnection: InputConnection,
        outputMode: String?,
        pipeline: String,
        fixMode: Boolean = false,
        onComplete: ((String?) -> Unit)? = null,
    ) {
        // ToT pipelines always use strip mode (one candidate per branch)
        val effectiveMode = if (pipeline == "tot") {
            "strip"
        } else {
            outputMode ?: DEFAULT_MODE
        }

        // Trap "overlay" and "strip" modes need to collect full output first
        if (effectiveMode == "overlay" || effectiveMode == "strip") {
            collectThenRender(tokenFlow, inputConnection, effectiveMode, fixMode, onComplete)
        } else {
            // "inline" streams directly
            inlineRenderer.stream(
                inputConnection = inputConnection,
                tokenFlow = tokenFlow,
                fixMode = fixMode,
                onComplete = onComplete,
            )
        }
    }

    /**
     * Render a pre-collected completion result (for non-streaming paths
     * or when the result is already fully available).
     */
    fun renderResult(
        result: CompletionResult,
        inputConnection: InputConnection,
        outputMode: String?,
        pipeline: String,
    ) {
        val effectiveMode = if (pipeline == "tot") "strip" else (outputMode ?: DEFAULT_MODE)
        val singleResult = listOf(result)

        when (effectiveMode) {
            "strip" -> stripRenderer.showResults(singleResult, pipeline)
            "overlay" -> overlayRenderer.show(result)
            else -> {
                // Inline with pre-collected result: commit immediately
                inputConnection.beginBatchEdit()
                inputConnection.commitText(result.text, 1)
                inputConnection.endBatchEdit()
            }
        }
    }

    // ── Collect-then-render for strip and overlay ────────────────────────

    private fun collectThenRender(
        tokenFlow: Flow<Token>,
        inputConnection: InputConnection,
        mode: String,
        fixMode: Boolean,
        onComplete: ((String?) -> Unit)?,
    ) {
        val fullText = StringBuilder()

        // Collect into InlineRenderer but buffer, don't commit
        // Actually, collect manually since we need the full text
        inlineRenderer.stream(
            inputConnection = inputConnection,
            tokenFlow = tokenFlow,
            fixMode = fixMode,
            onComplete = { text ->
                if (text != null) {
                    when (mode) {
                        "strip" -> {
                            val fakeResult = CompletionResult(
                                providerId = "local",
                                text = text,
                                inputTokens = 0,
                                outputTokens = 0,
                                latencyMs = 0,
                                success = true,
                            )
                            stripRenderer.showResults(listOf(fakeResult), "cot")
                        }
                        "overlay" -> {
                            val fakeResult = CompletionResult(
                                providerId = "local",
                                text = text,
                                inputTokens = 0,
                                outputTokens = 0,
                                latencyMs = 0,
                                success = true,
                            )
                            overlayRenderer.show(fakeResult)
                        }
                    }
                }
                onComplete?.invoke(text)
            },
        )
    }

    companion object {
        private const val DEFAULT_MODE = "inline"
    }
}
