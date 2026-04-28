package dev.patrickgold.florisboard.ime.ai.output

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ime.ai.providers.FinishReason
import dev.patrickgold.florisboard.ime.ai.providers.Token
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test for the Layer 5 output rendering pipeline.
 *
 * Tests the full flow: type a trigger → mock provider → OutputModeRouter
 * dispatches to InlineRenderer → text appears in EditText.
 *
 * Uses ActivityScenario to launch a lightweight test Activity with an
 * EditText, which provides a real InputConnection for the renderer to use.
 */
@RunWith(AndroidJUnit4::class)
class OutputRendererInstrumentationTest {

    private lateinit var scenario: ActivityScenario<TestActivity>
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scenario = ActivityScenario.launch(TestActivity::class.java)
    }

    @After
    fun teardown() {
        scope.cancel()
        scenario.close()
    }

    // ── Test 1: Inline mode streams text into EditText ───────────────────

    @Test
    fun inlineRenderer_streamsTextIntoEditText() {
        scenario.onActivity { activity ->
            val editText = activity.editText
            val inputConnection = editText.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            // Type "/doc test" into the EditText
            activity.runOnUiThread {
                editText.setText("")
                editText.requestFocus()
                // Simulate typing
                inputConnection?.commitText("/doc test", 1)
            }

            // Create mock token stream simulating provider output
            val mockFlow = flow {
                emit(Token(text = "Layered context architecture"))
                emit(Token(text = " separates concerns into"))
                emit(Token(text = " distinct layers."))
                emit(Token(text = "", finishReason = FinishReason.STOP))
            }

            // Create inline renderer and stream
            val renderer = InlineRenderer(scope)

            runBlocking(Dispatchers.Main) {
                renderer.stream(
                    inputConnection = inputConnection!!,
                    tokenFlow = mockFlow,
                    fixMode = false,
                )

                // Wait for streaming to complete
                delay(200)
            }

            // Assert text appears in EditText
            val finalText = editText.text.toString()
            assertTrue(
                "Expected 'Layered context architecture separates concerns into distinct layers.' in: '$finalText'",
                finalText.contains("Layered context architecture") &&
                        finalText.contains("separates concerns into") &&
                        finalText.contains("distinct layers.")
            )
        }
    }

    // ── Test 2: Cancel during streaming keeps partial text ───────────────

    @Test
    fun inlineRenderer_cancelKeepsPartialText() {
        scenario.onActivity { activity ->
            val editText = activity.editText
            val inputConnection = editText.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            activity.runOnUiThread {
                editText.setText("")
                editText.requestFocus()
            }

            val mockFlow = flow {
                emit(Token(text = "First part of the output "))
                emit(Token(text = "before cancellation"))
                // Never emit STOP — test forces cancel
            }

            val renderer = InlineRenderer(scope)

            runBlocking(Dispatchers.Main) {
                renderer.stream(
                    inputConnection = inputConnection!!,
                    tokenFlow = mockFlow,
                )

                delay(100) // let first token arrive
                renderer.cancel() // simulate user pressing a key
                delay(50)
            }

            val text = editText.text.toString()
            assertTrue(
                "Partial text should be preserved after cancel, got: '$text'",
                text.contains("First part")
            )
        }
    }

    // ── Test 3: /fix mode replaces selected text ─────────────────────────

    @Test
    fun inlineRenderer_fixModeReplacesSelection() {
        scenario.onActivity { activity ->
            val editText = activity.editText
            val inputConnection = editText.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            activity.runOnUiThread {
                editText.setText("This has a grammer mistake")
                editText.requestFocus()
                // Select the word "grammer" (positions 10-17)
                editText.setSelection(10, 17)
            }

            val mockFlow = flow {
                emit(Token(text = "grammar"))
                emit(Token(text = "", finishReason = FinishReason.STOP))
            }

            val renderer = InlineRenderer(scope)

            runBlocking(Dispatchers.Main) {
                renderer.stream(
                    inputConnection = inputConnection!!,
                    tokenFlow = mockFlow,
                    fixMode = true,
                )

                delay(200)
            }

            val fixedText = editText.text.toString()
            assertEquals(
                "Fix mode should replace 'grammer' with 'grammar'",
                "This has a grammar mistake",
                fixedText
            )
        }
    }

    // ── Test 4: Strip renderer shows candidates ─────────────────────────

    @Test
    fun stripRenderer_showsCandidates() {
        val stripRenderer = StripRenderer()
        val candidate1 = dev.patrickgold.florisboard.ime.ai.providers.CompletionResult(
            providerId = "local", text = "Option one", inputTokens = 0, outputTokens = 0,
            latencyMs = 0, success = true,
        )
        val candidate2 = dev.patrickgold.florisboard.ime.ai.providers.CompletionResult(
            providerId = "local", text = "Option two", inputTokens = 0, outputTokens = 0,
            latencyMs = 0, success = true,
        )

        stripRenderer.showResults(listOf(candidate1, candidate2), "tot")

        val candidates = stripRenderer.candidates.value
        assertEquals("Should have 2 candidates", 2, candidates.size)
        assertEquals("First candidate text", "Option one", candidates[0].text)
        assertEquals("Second candidate text", "Option two", candidates[1].text)
        assertTrue("First should have higher confidence", candidates[0].confidence >= candidates[1].confidence)
    }

    // ── Test 5: OutputModeRouter routes to correct renderer ──────────────

    @Test
    fun outputModeRouter_defaultsToInline() {
        scenario.onActivity { activity ->
            val editText = activity.editText
            val inputConnection = editText.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            activity.runOnUiThread {
                editText.setText("")
                editText.requestFocus()
            }

            val inlineRenderer = InlineRenderer(scope)
            val stripRenderer = StripRenderer()
            val overlayRenderer = OverlayRenderer(
                context = ApplicationProvider.getApplicationContext(),
                skillsJsonFile = java.io.File(activity.cacheDir, "test_skills.json"),
            )
            val router = OutputModeRouter(inlineRenderer, stripRenderer, overlayRenderer, scope)

            val mockFlow = flow {
                emit(Token(text = "Default inline output"))
                emit(Token(text = "", finishReason = FinishReason.STOP))
            }

            runBlocking(Dispatchers.Main) {
                router.render(
                    tokenFlow = mockFlow,
                    inputConnection = inputConnection!!,
                    outputMode = null, // no mode = defaults to inline
                    pipeline = "cot",
                )
                delay(200)
            }

            val text = editText.text.toString()
            assertTrue("Default mode should render inline", text.contains("Default inline output"))
        }
    }

    // ── Test 6: ToT pipeline forces strip mode ───────────────────────────

    @Test
    fun totPipeline_forcesStripMode() {
        val inlineRenderer = InlineRenderer(scope)
        val stripRenderer = StripRenderer()
        val overlayRenderer = OverlayRenderer(
            context = ApplicationProvider.getApplicationContext(),
            skillsJsonFile = java.io.File(""),
        )
        val router = OutputModeRouter(inlineRenderer, stripRenderer, overlayRenderer, scope)

        val mockFlow = flow {
            emit(Token(text = "ToT branch 1 result"))
            emit(Token(text = "", finishReason = FinishReason.STOP))
        }

        // Need an InputConnection — get one from a real View
        scenario.onActivity { activity ->
            val inputConnection = activity.editText.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            runBlocking(Dispatchers.Main) {
                router.render(
                    tokenFlow = mockFlow,
                    inputConnection = inputConnection!!,
                    outputMode = "inline", // explicitly set to inline
                    pipeline = "tot",       // but ToT overrides to strip
                )
                delay(200)
            }

            // Strip renderer should have received the candidate
            val candidates = stripRenderer.candidates.value
            assertTrue(
                "ToT should route to strip, candidates size: ${candidates.size}",
                candidates.isNotEmpty()
            )
        }
    }

    // ── Test: Test Activity definition ───────────────────────────────────

    /** Minimal Activity hosting an EditText for InputConnection testing. */
    class TestActivity : Activity() {
        lateinit var editText: EditText

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            editText = EditText(this)
            editText.id = android.R.id.edit
            val layout = LinearLayout(this).apply {
                addView(editText, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            setContentView(layout)
        }
    }
}
