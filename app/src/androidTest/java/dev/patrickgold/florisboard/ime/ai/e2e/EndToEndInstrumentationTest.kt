package dev.patrickgold.florisboard.ime.ai.e2e

import android.app.Activity
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ime.ai.orchestration.AppContext
import dev.patrickgold.florisboard.ime.ai.orchestration.ContextResolver
import dev.patrickgold.florisboard.ime.ai.orchestration.LlamaServerService
import dev.patrickgold.florisboard.ime.ai.output.InlineRenderer
import dev.patrickgold.florisboard.ime.ai.output.OutputModeRouter
import dev.patrickgold.florisboard.ime.ai.output.OverlayRenderer
import dev.patrickgold.florisboard.ime.ai.output.StripRenderer
import dev.patrickgold.florisboard.ime.ai.providers.CompletionRequest
import dev.patrickgold.florisboard.ime.ai.providers.CostLogger
import dev.patrickgold.florisboard.ime.ai.providers.FinishReason
import dev.patrickgold.florisboard.ime.ai.providers.Provider
import dev.patrickgold.florisboard.ime.ai.providers.ProviderConfig
import dev.patrickgold.florisboard.ime.ai.providers.Token
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumentation test for the full Layer 2–5 pipeline.
 *
 * Scenario:
 *   1. Obsidian is the foreground app with window title "MyVault - daily/2026-04-25.md - Obsidian"
 *   2. User types "/doc explain layered context" in the EditText
 *   3. Local LLM returns canned stream "FINAL: Layered context..."
 *   4. Assert context resolution, text insertion, and cost logging
 */
@RunWith(AndroidJUnit4::class)
class EndToEndInstrumentationTest {

    private lateinit var scenario: ActivityScenario<TestActivity>
    private lateinit var scope: CoroutineScope
    private lateinit var costLogger: CostLogger

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        costLogger = CostLogger()
        scenario = ActivityScenario.launch(TestActivity::class.java)
    }

    @After
    fun teardown() {
        scope.cancel()
        scenario.close()
    }

    @Test
    fun e2e_obsidianDocTrigger_resolvesContextAndInsertsText() {
        scenario.onActivity { activity ->
            val editText = activity.editText
            val inputConnection = editText.onCreateInputConnection(EditorInfo())

            // ── Step 1: Resolve Obsidian context from AccessibilityNodeInfo ──
            val contextResolver = ContextResolver()

            // Build a fake AccessibilityNodeInfo tree simulating the Obsidian
            // window: root node with a child that bears the window title text.
            val titleNode = AccessibilityNodeInfo.obtain().apply {
                text = "MyVault - daily/2026-04-25.md - Obsidian"
            }
            val rootNode = AccessibilityNodeInfo.obtain().apply {
                addChild(titleNode)
            }

            val appContext: AppContext = contextResolver.resolve(rootNode)

            assertEquals(
                "ContextResolver should extract vault name from Obsidian title",
                "MyVault", appContext.vaultName,
            )
            assertEquals(
                "ContextResolver should extract file path from Obsidian title",
                "daily/2026-04-25.md", appContext.filePath,
            )

            // Recycle test nodes
            titleNode.recycle()
            rootNode.recycle()

            // ── Step 2: Type the trigger + prompt into the EditText ──
            activity.runOnUiThread {
                editText.setText("")
                editText.requestFocus()
                inputConnection?.commitText("/doc explain layered context", 1)
            }

            // ── Step 3: Mock the LlamaServerService with canned stream ──
            val mockProvider = createMockProvider(
                streamText = "FINAL: Layered context architecture separates concerns into distinct layers."
            )
            val llamaService = LlamaServerService(mockProvider, costLogger)

            // ── Step 4: Pipe through OutputModeRouter → InlineRenderer ──
            val inlineRenderer = InlineRenderer(scope)
            val stripRenderer = StripRenderer()
            val overlayRenderer = OverlayRenderer(
                context = activity,
                skillsJsonFile = java.io.File(activity.cacheDir, "e2e_test_skills.json"),
            )
            val router = OutputModeRouter(inlineRenderer, stripRenderer, overlayRenderer, scope)

            // Collect the output into a buffer rather than streaming inline,
            // so we can verify the LlamaServerService cost logging works.
            val mockTokenFlow = mockProvider.complete(
                CompletionRequest(system = "", user = "/doc explain layered context", temperature = 0.3f, maxTokens = 256, timeoutMs = 5000)
            )

            runBlocking(Dispatchers.Main) {
                router.render(
                    tokenFlow = mockTokenFlow,
                    inputConnection = inputConnection!!,
                    outputMode = "inline",
                    pipeline = "cot",
                )
                // Wait for streaming to complete
                delay(500)
            }

            // ── Step 5: Use LlamaServerService to trigger cost logging ──
            runBlocking(Dispatchers.Main) {
                llamaService.complete(
                    request = CompletionRequest(
                        system = "You are writing inside Obsidian vault 'MyVault'. Active note: daily/2026-04-25.md",
                        user = "/doc explain layered context",
                        temperature = 0.3f,
                        maxTokens = 2048,
                        timeoutMs = 5000,
                    ),
                    triggerId = "/doc",
                )
                delay(200)
            }

            // ── Assertions ──

            // a) Text appears in EditText
            val finalText = editText.text.toString()
            assertTrue(
                "EditText should contain AI output. Got: '$finalText'",
                finalText.contains("Layered context architecture") &&
                        finalText.contains("separates concerns"),
            )

            // b) CostLogger has 1 entry for provider=local
            val entries = costLogger.entries.value
            assertEquals("Cost log should have exactly 1 entry", 1, entries.size)
            assertEquals("Cost entry provider should be 'local'", "local", entries[0].providerId)
            assertTrue("Cost entry should have output text", entries[0].outputChars > 0)
            assertTrue("Cost entry should be marked success", entries[0].success)
            assertNull("Cost entry should have no error", entries[0].error)
            assertEquals("Trigger ID should match", "/doc", entries[0].triggerId)
        }
    }

    // ── Test: ContextResolver extracts null when no Obsidian window ──

    @Test
    fun contextResolver_returnsNullsForNonObsidianWindow() {
        val contextResolver = ContextResolver()
        val result = contextResolver.resolveFromTitle("Chrome")
        assertNull("vaultName should be null for non-Obsidian title", result.vaultName)
        assertNull("filePath should be null for non-Obsidian title", result.filePath)
        assertEquals("windowTitle should be preserved", "Chrome", result.windowTitle)
    }

    @Test
    fun contextResolver_returnsNullsForBlankTitle() {
        val contextResolver = ContextResolver()
        assertNull("null title", contextResolver.resolveFromTitle(null).vaultName)
        assertNull("empty title", contextResolver.resolveFromTitle("").vaultName)
        assertNull("blank title", contextResolver.resolveFromTitle("   ").vaultName)
    }

    // ── Test: LlamaServerService logs cost on failure ──

    @Test
    fun llamaService_logsCostOnFailure() {
        val failingProvider = createFailingProvider("Connection refused")
        val localCostLogger = CostLogger()
        val service = LlamaServerService(failingProvider, localCostLogger)

        runBlocking {
            val result = service.complete(
                CompletionRequest(system = "", user = "test", temperature = 0.3f, maxTokens = 256, timeoutMs = 1000),
                triggerId = "/test",
            )
            assertNull("Result should be null on failure", result)

            val entries = localCostLogger.entries.value
            assertEquals("Should have 1 cost entry", 1, entries.size)
            assertFalse("Entry should be marked failure", entries[0].success)
            assertEquals("Provider ID should match", "failing", entries[0].providerId)
            assertNotNull("Error should be present", entries[0].error)
        }
    }

    // ── Factory helpers ───────────────────────────────────────────────────

    private fun createMockProvider(streamText: String): Provider {
        return object : Provider {
            override val id = "local"
            override val displayName = "Mock Local LLM"
            override val config = ProviderConfig(url = "mock://localhost:8080", model = "mock-model")
            override fun complete(request: CompletionRequest): Flow<Token> = flow {
                // Split on spaces to simulate token streaming
                val words = streamText.split(" ")
                for ((i, word) in words.withIndex()) {
                    val suffix = if (i < words.size - 1) " " else ""
                    emit(Token(text = word + suffix))
                }
                emit(Token(text = "", finishReason = FinishReason.STOP))
            }
        }
    }

    private fun createFailingProvider(errorMessage: String): Provider {
        return object : Provider {
            override val id = "failing"
            override val displayName = "Failing Mock Provider"
            override val config = ProviderConfig(url = "mock://fail")
            override fun complete(request: CompletionRequest): Flow<Token> = flow {
                emit(Token(text = errorMessage, finishReason = FinishReason.ERROR))
            }
        }
    }

    // ── Test Activity ─────────────────────────────────────────────────────

    class TestActivity : Activity() {
        lateinit var editText: EditText

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            editText = EditText(this)
            editText.id = android.R.id.edit
            val layout = LinearLayout(this).apply {
                addView(editText, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
            setContentView(layout)
        }
    }
}
