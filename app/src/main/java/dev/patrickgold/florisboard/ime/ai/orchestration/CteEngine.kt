package dev.patrickgold.florisboard.ime.ai.orchestration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.widget.Toast
import dev.patrickgold.florisboard.ime.ai.output.InlineRenderer
import dev.patrickgold.florisboard.ime.ai.output.OutputModeRouter
import dev.patrickgold.florisboard.ime.ai.output.OverlayRenderer
import dev.patrickgold.florisboard.ime.ai.output.StripRenderer
import dev.patrickgold.florisboard.ime.ai.bridges.AppProfileManager
import dev.patrickgold.florisboard.ime.ai.bridges.ObsidianBridge
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
    private val obsidianBridge: ObsidianBridge? = null,
    private val appProfileManager: AppProfileManager? = null,
) {
    companion object {
        private const val TAG = "CteEngine"
        private const val TAG_CTE = "CTE"
    }

    // ── Lazy pipeline components (initialized on first trigger) ──────────

    private val configStore = TriggerConfigStore.getInstance(context)

    // KeyVault is accessed lazily — getInstance() is safe at any time but
    // actual prefs reads are deferred until device is unlocked (see KeyVault).
    private val keyVault by lazy { KeyVault.getInstance(context) }

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
                val rawSystem = triggerDef.systemTemplate
                val rawUser = triggerResult.text
                val (systemPrompt, userPrompt) = resolveTemplateVariables(rawSystem, rawUser)

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

    // ── Template variable resolution ────────────────────────────────────

    /**
     * Resolves CTE template variables ({{vault.name}}, {{file.path}},
     * {{system.time.iso}}, {{system.tz}}, {{#each file.tags}}...{{/each}}
     * blocks) in system and user prompt strings.
     *
     * Vault name comes from [ObsidianBridge] (manually configured).
     * File path and tags degrade gracefully to empty strings / removed blocks
     * when window title is unavailable (no AccessibilityService yet).
     */
    private fun resolveTemplateVariables(
        systemPrompt: String,
        userPrompt: String,
    ): Pair<String, String> {
        var resolvedSystem = systemPrompt
        var resolvedUser = userPrompt

        // ── Resolve {{vault.name}} ──
        val vaultName = obsidianBridge?.getVaultName()
        if (vaultName != null) {
            resolvedSystem = resolvedSystem.replace("{{vault.name}}", vaultName)
            resolvedUser = resolvedUser.replace("{{vault.name}}", vaultName)
        } else {
            // Graceful degradation: replace with empty string if not configured
            resolvedSystem = resolvedSystem.replace("{{vault.name}}", "")
            resolvedUser = resolvedUser.replace("{{vault.name}}", "")
        }

        // ── Resolve {{file.path}} ──
        // Requires window title from AccessibilityService — graceful degradation
        resolvedSystem = resolvedSystem.replace("{{file.path}}", "")
        resolvedUser = resolvedUser.replace("{{file.path}}", "")

        // ── Resolve {{file.tags|none}} and {{file.tags}} ──
        resolvedSystem = resolvedSystem.replace("{{file.tags|none}}", "")
        resolvedSystem = resolvedSystem.replace("{{file.tags}}", "")
        resolvedUser = resolvedUser.replace("{{file.tags|none}}", "")
        resolvedUser = resolvedUser.replace("{{file.tags}}", "")

        // ── Strip {{#each file.tags}}...{{/each}} blocks ──
        // Handlebars-style iteration — remove entirely when tags are unavailable
        resolvedSystem = removeEachBlock(resolvedSystem, "file.tags")
        resolvedUser = removeEachBlock(resolvedUser, "file.tags")

        // ── Resolve {{system.time.iso}} ──
        val now = java.time.LocalDateTime.now()
        val iso = now.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        resolvedSystem = resolvedSystem.replace("{{system.time.iso}}", iso)
        resolvedUser = resolvedUser.replace("{{system.time.iso}}", iso)

        // ── Resolve {{system.tz}} ──
        val tz = java.time.ZoneId.systemDefault().id
        resolvedSystem = resolvedSystem.replace("{{system.tz}}", tz)
        resolvedUser = resolvedUser.replace("{{system.tz}}", tz)

        // ── Resolve {{system.selection}} ──
        // Resolve to empty string (selection is already in user_input if active)
        resolvedSystem = resolvedSystem.replace("{{system.selection}}", "")
        resolvedUser = resolvedUser.replace("{{system.selection}}", "")

        // ── Strip any remaining unresolved {{variables}} gracefully ──
        // Leave {{user_input}} intact — it's handled separately in the pipeline
        resolvedSystem = stripUnresolvedTemplateVars(resolvedSystem)
        resolvedUser = stripUnresolvedTemplateVars(resolvedUser)

        return Pair(resolvedSystem, resolvedUser)
    }

    /**
     * Remove a {{#each varName}}...{{/each}} block from text.
     * Simple substring-based approach (not regex) to avoid KSP escaping issues.
     */
    private fun removeEachBlock(text: String, varName: String): String {
        val startTag = "{{#each $varName}}"
        val endTag = "{{/each}}"
        val sb = StringBuilder()
        var remaining = text
        while (true) {
            val startIdx = remaining.indexOf(startTag)
            if (startIdx < 0) {
                sb.append(remaining)
                break
            }
            sb.append(remaining.substring(0, startIdx))
            val endIdx = remaining.indexOf(endTag, startIdx + startTag.length)
            if (endIdx < 0) {
                // No closing tag — skip the rest
                remaining = ""
                break
            }
            remaining = remaining.substring(endIdx + endTag.length)
        }
        return sb.toString()
    }

    /**
     * Strip unresolved {{variable}} placeholders while preserving {{user_input}}.
     */
    private fun stripUnresolvedTemplateVars(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val openIdx = text.indexOf("{{", i)
            if (openIdx < 0) {
                sb.append(text.substring(i))
                break
            }
            sb.append(text.substring(i, openIdx))
            val closeIdx = text.indexOf("}}", openIdx + 2)
            if (closeIdx < 0) {
                sb.append(text.substring(openIdx))
                break
            }
            val varName = text.substring(openIdx + 2, closeIdx).trim()
            if (varName == "user_input" || varName.startsWith("user_input")) {
                // Preserve {{user_input}} and {{user_input|...}}
                sb.append(text.substring(openIdx, closeIdx + 2))
            }
            // else: drop the entire {{...}} placeholder
            i = closeIdx + 2
        }
        return sb.toString()
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
                    url      = obj["url"]?.jsonPrimitive?.content ?: "",
                    model    = obj["model"]?.jsonPrimitive?.content,
                    keyRef   = obj["keyRef"]?.jsonPrimitive?.content,
                    priority = obj["priority"]?.jsonPrimitive?.int ?: 10,
                    maxTokens = obj["maxTokens"]?.jsonPrimitive?.int ?: 2048,
                    timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.long ?: 30_000L,
                    enabled  = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    role     = obj["role"]?.jsonPrimitive?.content ?: "primary",
                )
                providerConfigMap[providerId] = config

                // Skip disabled providers entirely
                if (!config.enabled) {
                    Log.d(TAG, "Provider $providerId skipped (enabled=false)")
                    continue
                }

                // Create provider instance if API key is available (or no key needed)
                val apiKey = config.keyRef?.let { keyVault.get(it) }

                val provider: Provider? = when (providerId) {
                    "local"      -> LlamaCppLocal(config)
                    "anthropic"  -> if (apiKey != null) AnthropicProvider(config, apiKey) else null
                    "openai"     -> if (apiKey != null) OpenAIProvider(config, apiKey) else null
                    "groq"       -> if (apiKey != null) GroqProvider(config, apiKey) else null
                    "cerebras"   -> if (apiKey != null) CerebrasProvider(config, apiKey) else null
                    "openrouter" -> if (apiKey != null) OpenRouterProvider(config, apiKey) else null
                    "deepseek"   -> if (apiKey != null) DeepseekProvider(config, apiKey) else null
                    else         -> {
                        // gemini_1, gemini_2, or future providers
                        if (providerId.startsWith("gemini") && apiKey != null)
                            GeminiProvider(config, apiKey)
                        else {
                            Log.w(TAG, "Unknown or unconfigured provider: $providerId")
                            null
                        }
                    }
                }

                if (provider != null) {
                    providerInstances[providerId] = provider
                    Log.d(TAG, "Provider $providerId ready (role=${config.role})")
                } else if (config.keyRef != null) {
                    Log.i(TAG, "Provider $providerId skipped — no key set for ${config.keyRef}")
                }
            }

            Log.i(TAG, "Built ${providerInstances.size} provider instances " +
                    "(configured: ${providerConfigMap.size})")

            if (providerInstances.isEmpty()) {
                Log.w(TAG, "No providers with API keys available")
                showFallback("[CTE: set API keys in AI Settings → Manage API Keys]")
                return null
            }

            // Build routing config — load from routing.json
            val routingFile = File(configStore.getConfigsDir(), "routing.json")
            val routingDefaults: JsonObject = if (routingFile.exists()) {
                try {
                    Json.parseToJsonElement(routingFile.readText()).jsonObject
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse routing.json, using defaults", e)
                    JsonObject(emptyMap())
                }
            } else {
                Log.w(TAG, "routing.json not found at ${routingFile.path}; using defaults")
                JsonObject(emptyMap())
            }

            val routingRules = routingDefaults["rules"]?.jsonArray?.mapNotNull { el ->
                val ruleObj = el.jsonObject
                val condition = ruleObj["if"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val use = ruleObj["use"]?.jsonPrimitive?.content ?: return@mapNotNull null
                RoutingRule(condition, use)
            } ?: emptyList()

            val budgets = routingDefaults["budgets"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyMap()

            val routingConfig = RoutingConfig(
                default = routingDefaults["default"]?.jsonPrimitive?.content
                    ?: providerInstances.keys.firstOrNull() ?: "local",
                rules = routingRules,
                providers = providerConfigMap,
                budgets = budgets,
            )

            // Build pipeline components
            val healthTracker = HealthTracker()
            val ruleParser = RuleParser()
            val inlineRenderer = InlineRenderer(
                scope = scope,
                onCancelled = { tokens ->
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            if (tokens > 0) "Generation stopped — $tokens tokens kept"
                            else "Generation cancelled",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
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

    /**
     * Reloads all configuration from disk: clears the cached trigger map
     * and pipeline so the next trigger detection re-reads triggers.json,
     * routing.json, and reinstantiates providers.
     *
     * Call from CteSettingsActivity "Reload config" button.
     *
     * @return true if the config tree was found and reload was queued,
     *         false if the config directory is missing.
     */
    fun reloadConfig(): Boolean {
        val configDir = configStore.getConfigsDir()
        if (!configDir.exists()) {
            Log.w(TAG, "reloadConfig: config dir missing at ${configDir.path}")
            return false
        }
        // Invalidate memory caches so the next trigger re-reads from disk
        registeredTriggers = null
        pipeline = null
        pipelineInitAttempted = false

        Log.i(TAG, "CteEngine caches cleared — next trigger will reload from disk")
        return true
    }
}
