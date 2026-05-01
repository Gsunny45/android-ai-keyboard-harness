package dev.patrickgold.florisboard.ime.ai.providers

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Routes a [ResolvedPrompt] to the appropriate provider by evaluating
 * routing rules in order. Falls back through the provider priority chain
 * if the selected provider is unhealthy or fails.
 *
 * Routing flow:
 *   1. Evaluate routing.rules in definition order
 *   2. First rule whose condition matches wins
 *   3. If no rule matches, use routing.default
 *   4. If selected provider is unhealthy, try fallback_provider
 *   5. If fallback also fails, iterate priority-sorted providers
 *   6. If all providers fail, emit [Token] with FinishReason.ERROR
 *
 * Toggle / role-aware routing:
 *   - [ProviderConfig.enabled] = false → provider is completely skipped.
 *   - [ProviderRole.FINISHER]  → only selected for ToT pipeline or maxTokens > 1024.
 *   - [ProviderRole.FALLBACK]  → only selected after all PRIMARY providers fail.
 *   - Providers with a keyRef but no stored key are also skipped.
 */
class ProviderRouter(
    private val providers: Map<String, Provider>,
    private val healthTracker: HealthTracker,
    private val ruleParser: RuleParser,
    private val routingConfig: RoutingConfig,
    private val keyVault: KeyVault,
    private val costLogger: CostLogger = CostLogger(),
) {
    companion object {
        private const val TAG = "ProviderRouter"
    }

    /**
     * Route and complete a resolved prompt.
     * Returns a Flow so the caller can stream tokens as they arrive.
     */
    fun route(prompt: ResolvedPrompt): Flow<Token> = flow {
        val startTime = System.currentTimeMillis()
        val selectedProviderId = selectProviderId(prompt)
        val provider = providers[selectedProviderId]

        if (provider == null) {
            healthTracker.recordFailure(selectedProviderId, "Provider not found: $selectedProviderId")
            costLogger.log(selectedProviderId, prompt.system + prompt.user, "", 0L, false, "Provider not found")
            emit(Token("", FinishReason.ERROR))
            return@flow
        }

        val timeoutMs = provider.config.timeoutMs
        val request = CompletionRequest(
            system = prompt.system,
            user = prompt.user,
            temperature = prompt.temperature,
            maxTokens = prompt.maxTokens,
            timeoutMs = timeoutMs,
        )

        // Try selected provider; on failure try fallback
        val result = tryProviderWithFallback(provider, request, selectedProviderId, prompt, startTime)

        val elapsed = System.currentTimeMillis() - startTime
        costLogger.log(
            providerId = result.providerId,
            inputText = prompt.system + prompt.user,
            outputText = result.text,
            latencyMs = elapsed,
            success = result.success,
            error = result.error,
        )

        if (result.success) {
            emit(Token(result.text, FinishReason.STOP))
        } else {
            emit(Token("", FinishReason.ERROR))
        }
    }

    // ── Toggle / role checks ─────────────────────────────────────────────

    /**
     * Returns true if the provider's master [ProviderConfig.enabled] flag is on.
     * This is the first gate — a disabled provider is skipped before any other check.
     */
    private fun providerIsEnabled(providerId: String): Boolean {
        val cfg = providers[providerId]?.config ?: return false
        if (!cfg.enabled) {
            Log.d(TAG, "Provider $providerId skipped — enabled=false")
        }
        return cfg.enabled
    }

    /**
     * Returns true if [providerId] is eligible given the current [prompt].
     *
     * Role rules:
     *   FINISHER → only allowed when pipeline == "tot" OR maxTokens > 1024
     *   FALLBACK → deferred; not eligible in the primary selection pass
     *   PRIMARY  → always eligible (subject to health + key checks)
     */
    private fun providerRoleAllows(providerId: String, prompt: ResolvedPrompt): Boolean {
        val role = providers[providerId]?.config?.providerRole ?: return true
        return when (role) {
            ProviderRole.FINISHER -> prompt.pipeline == "tot" || prompt.maxTokens > 1024
            ProviderRole.FALLBACK -> false  // handled separately in fallback pass
            ProviderRole.PRIMARY  -> true
        }
    }

    // ── Key availability check ────────────────────────────────────────────

    /**
     * Returns true if the provider identified by [providerId] has an API key
     * available. Providers without a keyRef (e.g. local) always pass.
     * Providers with a keyRef that has no stored key are treated as disabled.
     */
    private fun providerHasApiKey(providerId: String): Boolean {
        val provider = providers[providerId] ?: return false
        val keyRef = provider.config.keyRef ?: return true // no keyRef = no key needed
        val hasKey = keyVault.get(keyRef) != null
        if (!hasKey) {
            Log.w(TAG, "Provider $providerId skipped — no key stored for keyRef=$keyRef")
        }
        return hasKey
    }

    // ── Provider selection ───────────────────────────────────────────────

    private fun selectProviderId(prompt: ResolvedPrompt): String {
        // Full eligibility gate: enabled + correct role for this prompt + healthy + has key
        fun isAvailable(id: String): Boolean =
            providerIsEnabled(id) &&
            providerRoleAllows(id, prompt) &&
            healthTracker.isHealthy(id) &&
            providerHasApiKey(id)

        // Fallback-role eligibility (used only in the final fallback pass)
        fun isFallbackAvailable(id: String): Boolean =
            providerIsEnabled(id) &&
            providers[id]?.config?.providerRole == ProviderRole.FALLBACK &&
            healthTracker.isHealthy(id) &&
            providerHasApiKey(id)

        // 1. Explicit preference from trigger (bypass role check — caller knows what they want)
        if (prompt.preferredProvider != null) {
            val id = prompt.preferredProvider
            if (providerIsEnabled(id) && healthTracker.isHealthy(id) && providerHasApiKey(id)) {
                return id
            }
        }

        // 2. Budget-based override — reads ordered provider list from routing.json
        val budgetPreferred = routingConfig.budgets[prompt.budget]
        if (budgetPreferred != null && budgetPreferred.isNotEmpty()) {
            for (id in budgetPreferred) {
                if (isAvailable(id)) return id
            }
        }

        // 3. Evaluate routing rules in order (role gate still applies)
        val context = RuleContext(
            triggerPipeline = prompt.pipeline,
            triggerMaxTokens = prompt.maxTokens,
            triggerBudget = prompt.budget,
            providerHealth = routingConfig.providers.keys.associateWith { healthTracker.getHealth(it) },
        )

        for (rule in routingConfig.rules) {
            try {
                val expr = ruleParser.parse(rule.condition)
                if (ruleParser.evaluate(expr, context)) {
                    val target = rule.use
                    if (isAvailable(target)) return target
                }
            } catch (_: RuleParseException) {
                // Skip malformed rules silently
            }
        }

        // 4. Default provider
        if (isAvailable(routingConfig.default)) return routingConfig.default

        // 5. First available PRIMARY/FINISHER provider by priority
        val byPriority = routingConfig.providers
            .entries
            .sortedBy { it.value.priority }
            .map { it.key }

        byPriority.firstOrNull { isAvailable(it) }?.let { return it }

        // 6. Last resort — try FALLBACK-role providers (all primaries exhausted)
        byPriority.firstOrNull { isFallbackAvailable(it) }?.let {
            Log.i(TAG, "All primary providers exhausted — trying fallback provider: $it")
            return it
        }

        return routingConfig.providers.keys.firstOrNull() ?: "local"
    }

    // ── Fallback execution ───────────────────────────────────────────────

    private suspend fun tryProviderWithFallback(
        primary: Provider,
        request: CompletionRequest,
        primaryId: String,
        prompt: ResolvedPrompt,
        startTime: Long,
    ): CompletionResult {
        // Try primary provider (only if it has its API key)
        val primaryResult = if (providerHasApiKey(primaryId)) {
            executeProvider(primary, request, primaryId)
        } else {
            // Don't attempt — log already emitted by providerHasApiKey
            CompletionResult(primaryId, "", 0, 0, 0L, false, "No API key configured")
        }
        if (primaryResult.success) return primaryResult

        // Try explicit fallback_provider from trigger config
        if (prompt.fallbackProvider != null) {
            val fallback = providers[prompt.fallbackProvider]
            if (fallback != null && providerHasApiKey(prompt.fallbackProvider)) {
                val fallbackResult = executeProvider(fallback, request, prompt.fallbackProvider)
                if (fallbackResult.success) return fallbackResult
            }
        }

        // Try any remaining healthy provider by priority (respect enabled + role)
        val priorityOrder = routingConfig.providers
            .entries
            .sortedBy { it.value.priority }
            .map { it.key }

        for (providerId in priorityOrder) {
            if (providerId == primaryId || providerId == prompt.fallbackProvider) continue
            if (providerId !in providers) continue
            if (!providerIsEnabled(providerId)) continue
            if (!healthTracker.isHealthy(providerId)) continue
            if (!providerHasApiKey(providerId)) continue
            // FINISHER-role providers: only include if prompt warrants it
            val role = providers[providerId]?.config?.providerRole
            if (role == ProviderRole.FINISHER && prompt.pipeline != "tot" && prompt.maxTokens <= 1024) continue

            val candidate = providers[providerId]!!
            val candidateResult = executeProvider(candidate, request, providerId)
            if (candidateResult.success) return candidateResult
        }

        // All providers failed
        return primaryResult // return the original failure
    }

    private suspend fun executeProvider(
        provider: Provider,
        request: CompletionRequest,
        providerId: String,
    ): CompletionResult {
        val start = System.currentTimeMillis()
        try {
            val textBuilder = StringBuilder()
            var streamError = false
            provider.complete(request).collect { token ->
                if (token.finishReason == FinishReason.ERROR) {
                    healthTracker.recordFailure(providerId, "Provider returned error")
                    streamError = true
                    return@collect
                }
                textBuilder.append(token.text)
            }
            if (streamError) {
                return@executeProvider CompletionResult(providerId, textBuilder.toString(), 0, 0, System.currentTimeMillis() - start, false, "Stream error")
            }
            val elapsed = System.currentTimeMillis() - start
            healthTracker.recordSuccess(providerId, elapsed)
            return CompletionResult(
                providerId = providerId,
                text = textBuilder.toString(),
                inputTokens = CostLogger.estimateTokens(request.system + request.user),
                outputTokens = CostLogger.estimateTokens(textBuilder.toString()),
                latencyMs = elapsed,
                success = true,
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            healthTracker.recordFailure(providerId, e.message ?: e.javaClass.simpleName)
            return CompletionResult(providerId, "", 0, 0, elapsed, false, e.message ?: "Unknown error")
        }
    }
}

/**
 * Routing configuration loaded from routing.json.
 */
data class RoutingConfig(
    val default: String = "local",
    val rules: List<RoutingRule> = emptyList(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    /** Budget tier -> ordered provider ID list. Empty list = skip to normal routing. */
    val budgets: Map<String, List<String>> = emptyMap(),
)

data class RoutingRule(
    val condition: String,
    val use: String,
)
