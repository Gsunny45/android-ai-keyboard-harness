package dev.patrickgold.florisboard.ime.ai.orchestration

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.ai.output.InlineRenderer
import dev.patrickgold.florisboard.ime.ai.output.OutputModeRouter
import dev.patrickgold.florisboard.ime.ai.output.OverlayRenderer
import dev.patrickgold.florisboard.ime.ai.output.StripRenderer
import dev.patrickgold.florisboard.ime.ai.providers.*
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

/**
 * Central CTE (Context-aware Type Engine) coordinator.
 *
 * Wires together trigger detection, provider routing, and output rendering
 * into the FlorisBoard IME's input pipeline.
 *
 * Architecture:
 *   - Trigger detection: scans text-before-cursor for registered trigger words
 *     (e.g., "/fix", "<<cot>>") on every updateSelection().
 *   - Provider routing: resolves the trigger to a configured provider chain,
 *     creates Provider instances (with API keys from KeyVault), and routes
 *     the prompt through ProviderRouter.
 *   - Output rendering: streams the response back through InlineRenderer
 *     (and eventually strip/overlay renderers).
 */
class CteEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "CteEngine"
        private const val TAG_CTE = "CTE"
    }

    // ── Lazy pipeline components (initialized on first trigger) ──────────

    private val configStore = TriggerConfigStore.getInstance(context)
    private val keyVault = KeyVault.getInstance(context)

    private var pipeline: CtePipeline? = null

    /** True if pipeline initialization has been attempted (success or fail). */
    private var pipelineInitAttempted = false

    /** Guards against re-triggering while a request is in flight. */
    @Volatile
    private var processing = false

    /** Timestamp of last trigger detection (debounce: 500ms). */
    private var lastTriggerTime = 0L

    /** InputConnection is cached for the duration of one trigger cycle. */
    private var activeInputConnection: InputConnection? = null

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called from FlorisImeService.onUpdateSelection().
     * Checks the text before the cursor for registered trigger patterns
     * and routes through the AI pipeline if a trigger is found.
     *
     * @param inputConnection The current InputConnection from the IME.
     * @param selectionStart The cursor position (start of selection).
     *        Typical text before cursor: the full text of the field up to
     *        the cursor position.
     */
    fun onSelectionChanged(inputConnection: InputConnection, selectionStart: Int) {
        if (processing) return

        // Debounce: don't check every single character
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 200) return
        lastTriggerTime = now

        // Get text before cursor (up to 200 chars)
        val textBefore = inputConnection.getTextBeforeCursor(200, 0)?.toString() ?: return
        if (textBefore.isBlank()) return

        // Scan for triggers
        val triggerResult = detectTrigger(textBefore) ?: return

        // Initialize pipeline if needed
        if (!pipelineInitAttempted) {
            pipeline = buildPipeline()
            pipelineInitAttempted = true
        }

        val p = pipeline ?: run {
            Log.w(TAG, "Pipeline not initialized; cannot process trigger '${triggerResult.trigger}'")
            showFallback(output = "[CTE: no providers configured — set API keys in AI Settings]")
            return
        }

        // Mark as processing
        processing = true
        activeInputConnection = inputConnection

        Log.i(TAG_CTE, "Trigger detected: '${triggerResult.trigger}' with text='${triggerResult.text}'")

        // Remove the trigger from the text field
        inputConnection.beginBatchEdit()
        val textBeforeCursor = inputConnection.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val triggerStart = textBeforeCursor.lastIndexOf(triggerResult.rawTrigger)
        if (triggerStart >= 0) {
            // Set cursor to start of trigger
            val cursorPos = selectionStart
            val triggerLen = triggerResult.rawTrigger.length
            // Delete backwards from current position
            inputConnection.setSelection(cursorPos - triggerLen, cursorPos)
            inputConnection.commitText("", 1)
        }
        inputConnection.endBatchEdit()

        // Build the prompt and route it
        scope.launch {
            try {
                val triggerDef = triggerResult.triggerDef
                val systemPrompt = triggerDef.systemTemplate
                val userPrompt = triggerResult.text

                val resolvedPrompt = ResolvedPrompt(
                    system = systemPrompt,
                    user = userPrompt,
                    temperature = 0.7f,
                    maxTokens = triggerDef.maxTokens,
                    pipeline = triggerDef.pipeline,
                    preferredProvider = triggerDef.provider,
                    fallbackProvider = triggerDef.fallbackProvider,
                    budget = triggerDef.budget,
                )

                val tokenFlow = p.providerRouter.route(resolvedPrompt)

                // Render output
                p.outputModeRouter.render(
                    tokenFlow = tokenFlow,
                    inputConnection = inputConnection,
                    outputMode = triggerDef.outputMode,
                    pipeline = triggerDef.pipeline,
                    fixMode = true,
                    onComplete = { result ->
                        if (result == null) {
                            Log.w(TAG_CTE, "Provider returned empty result for '${triggerResult.trigger}'")
                        } else {
                            Log.i(TAG_CTE, "Result for '${triggerResult.trigger}': ${result.take(100)}")
                        }
                        processing = false
                        activeInputConnection = null
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error processing '${triggerResult.trigger}'", e)
                showFallback(output = "[CTE error: ${e.message?.take(60)}]")
                processing = false
                activeInputConnection = null
            }
        }
    }

    // ── Fallback output (when pipeline is unavailable) ──────────────────

    /**
     * If the pipeline can't be initialized (no providers with keys),
     * show a message directly in the text field so the user knows
     * what's happening.
     */
    private fun showFallback(output: String) {
        val ic = activeInputConnection ?: return
        ic.beginBatchEdit()
        ic.commitText(output, 1)
        ic.endBatchEdit()
    }

    // ── Trigger detection ───────────────────────────────────────────────

    private data class TriggerResult(
        val trigger: String,          // e.g. "/fix"
        val text: String,             // remaining text after removing trigger
        val rawTrigger: String,       // the matched substring in the original
        val triggerDef: TriggerDef,
    )

    private data class TriggerDef(
        val provider: String,
        val fallbackProvider: String?,
        val systemTemplate: String,
        val userTemplate: String,
        val maxTokens: Int,
        val pipeline: String,
        val outputMode: String?,
        val budget: String,
    )

    /**
     * Scans [textBefore] for any registered trigger word.
     * Returns the first match found (scanning left-to-right).
     */
    private fun detectTrigger(textBefore: String): TriggerResult? {
        val triggers = registeredTriggers ?: return null
        val cleaned = textBefore.trimStart()

        // The trigger is a word surrounded by whitespace or at boundaries.
        // Strategy: find any word in the text that matches a registered trigger.
        val words = textBefore.split("\\s+".toRegex()).filter { it.isNotBlank() }

        for ((triggerId, def) in triggers) {
            // Check if this trigger appears as a complete word in the text
            for (word in words) {
                if (word == triggerId) {
                    // Found the trigger word — remove it from the text
                    val remainingWords = words.toMutableList().apply { remove(word) }
                    val remaining = remainingWords.joinToString(" ")

                    return TriggerResult(
                        trigger = triggerId,
                        text = remaining,
                        rawTrigger = word,
                        triggerDef = def,
                    )
                }
            }

            // Also check for "<<cot>>" or "<<tot>>" style triggers which
            // might not get split as complete words
            if (triggerId.startsWith("<<") && triggerId.endsWith(">>")) {
                val idx = textBefore.indexOf(triggerId)
                if (idx >= 0) {
                    val before = textBefore.substring(0, idx).trim()
                    val after = textBefore.substring(idx + triggerId.length).trim()
                    val textAfter = (before + " " + after).trim()

                    return TriggerResult(
                        trigger = triggerId,
                        text = textAfter,
                        rawTrigger = triggerId,
                        triggerDef = def,
                    )
                }
            }
        }

        return null
    }

    // ── Registered triggers (loaded from triggers.json) ─────────────────

    private var registeredTriggers: Map<String, TriggerDef>? = null

    /**
     * Load registered triggers from the on-disk triggers.json config.
     * Returns the trigger map and also sets it on [registeredTriggers].
     */
    private fun loadTriggersConfig(): Map<String, TriggerDef> {
        if (registeredTriggers != null) return registeredTriggers!!

        val configFile = File(configStore.getConfigsDir(), "triggers.json")
        if (!configFile.exists()) {
            Log.w(TAG, "triggers.json not found at ${configFile.path}")
            return emptyMap()
        }

        return try {
            val text = configFile.readText()
            val json = Json.parseToJsonElement(text).jsonObject
            val triggersObj = json["triggers"]?.jsonObject ?: return emptyMap()

            val result = mutableMapOf<String, TriggerDef>()
            for ((key, value) in triggersObj) {
                val obj = value.jsonObject
                val triggerDef = TriggerDef(
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "local",
                    fallbackProvider = obj["fallback_provider"]?.jsonPrimitive?.content,
                    systemTemplate = obj["system_template"]?.jsonPrimitive?.content ?: "",
                    userTemplate = obj["user_template"]?.jsonPrimitive?.content ?: "{{user_input}}",
                    maxTokens = obj["max_tokens"]?.jsonPrimitive?.int ?: 512,
                    pipeline = obj["pipeline"]?.jsonPrimitive?.content ?: "single",
                    outputMode = obj["output_mode"]?.jsonPrimitive?.content,
                    budget = obj["budget"]?.jsonPrimitive?.content ?: "balanced",
                )
                result[key] = triggerDef
            }
            Log.i(TAG, "Loaded ${result.size} triggers from config")
            registeredTriggers = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse triggers.json", e)
            emptyMap()
        }
    }

    // ── Pipeline construction ───────────────────────────────────────────

    private data class CtePipeline(
        val providerRouter: ProviderRouter,
        val outputModeRouter: OutputModeRouter,
    )

    /**
     * Builds the full CTE pipeline: loads provider configs, creates
     * Provider instances (if API keys are available), constructs the
     * ProviderRouter and OutputModeRouter.
     */
    private fun buildPipeline(): CtePipeline? {
        val configFile = File(configStore.getConfigsDir(), "triggers.json")
        if (!configFile.exists()) {
            Log.w(TAG, "triggers.json not found; cannot build pipeline")
            return null
        }

        return try {
            val text = configFile.readText()
            val json = Json.parseToJsonElement(text).jsonObject

            // Parse providers section
            val providersObj = json["providers"]?.jsonObject ?: return null
            val providerInstances = mutableMapOf<String, Provider>()
            val providerConfigMap = mutableMapOf<String, ProviderConfig>()

            for ((providerId, providerValue) in providersObj) {
                val obj = providerValue.jsonObject
                val config = ProviderConfig(
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    model = obj["model"]?.jsonPrimitive?.content,
                    keyRef = obj["keyRef"]?.jsonPrimitive?.content,
                    priority = obj["priority"]?.jsonPrimitive?.int ?: 10,
                    maxTokens = obj["maxTokens"]?.jsonPrimitive?.int ?: 2048,
                    timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.long ?: 30_000L,
                )
                providerConfigMap[providerId] = config

                // Create provider instance if API key is available (or no key needed)
                val apiKey = config.keyRef?.let { keyVault.get(it) }

                val provider: Provider? = when {
                    providerId == "local" -> LlamaCppLocal(config)
                    providerId.startsWith("gemini") -> {
                        if (apiKey != null) GeminiProvider(config, apiKey) else null
                    }
                    providerId == "anthropic" -> {
                        if (apiKey != null) AnthropicProvider(config, apiKey) else null
                    }
                    providerId == "openai" -> {
                        if (apiKey != null) OpenAIProvider(config, apiKey) else null
                    }
                    providerId == "deepseek" -> {
                        if (apiKey != null) DeepseekProvider(config, apiKey) else null
                    }
                    else -> {
                        Log.w(TAG, "Unknown provider type: $providerId")
                        null
                    }
                }

                if (provider != null) {
                    providerInstances[providerId] = provider
                }
            }

            Log.i(TAG, "Built ${providerInstances.size} provider instances " +
                    "(configured: ${providerConfigMap.size})")

            if (providerInstances.isEmpty()) {
                Log.w(TAG, "No providers with API keys available")
                showFallback("[CTE: set API keys in AI Settings → Manage API Keys]")
                return null
            }

            // Build routing config
            val routingConfig = RoutingConfig(
                default = json["routing"]?.jsonObject?.get("default")?.jsonPrimitive?.content
                    ?: providerInstances.keys.firstOrNull() ?: "local",
                rules = emptyList(),
                providers = providerConfigMap,
            )

            // Build pipeline components
            val healthTracker = HealthTracker()
            val ruleParser = RuleParser()
            val inlineRenderer = InlineRenderer(scope)
            val stripRenderer = StripRenderer()
            val skillsFile = File(configStore.getConfigsDir(), "skills.json")
            val overlayRenderer = OverlayRenderer(context, skillsFile)

            val providerRouter = ProviderRouter(
                providers = providerInstances,
                healthTracker = healthTracker,
                ruleParser = ruleParser,
                routingConfig = routingConfig,
                keyVault = keyVault,
            )

            val outputModeRouter = OutputModeRouter(
                inlineRenderer = inlineRenderer,
                stripRenderer = stripRenderer,
                overlayRenderer = overlayRenderer,
                scope = scope,
            )

            CtePipeline(
                providerRouter = providerRouter,
                outputModeRouter = outputModeRouter,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build CTE pipeline", e)
            null
        }
    }

    // ── Initialization helper ───────────────────────────────────────────

    /**
     * Pre-loads the triggers configuration. Call from IME onCreate
     * to avoid first-trigger latency. Safe to call multiple times.
     */
    fun warmUp() {
        loadTriggersConfig()
        Log.i(TAG, "CteEngine warmed up")
    }
}
