# Contributing to FlorisBoard Vault

## Project Structure

```
app/src/main/java/dev/patrickgold/florisboard/ime/ai/
├── orchestration/     # Context resolution, LlamaServerService
├── output/            # InlineRenderer, StripRenderer, OverlayRenderer, OutputModeRouter
├── providers/         # Provider interface, routing (RuleParser, ProviderRouter), logging
├── trigger/           # Trigger parsing (WIP)
├── voice/             # Voice input, spoken trigger normalization
└── bridges/           # Cross-layer bridges (WIP)

templates/
├── triggers.json          # Global trigger + provider config
├── obsidian.triggers.json # Obsidian-specific triggers
├── gmail.triggers.json    # Gmail-specific triggers
├── whatsapp.triggers.json # WhatsApp-specific triggers
└── per-app-profiles/      # App-to-persona mapping
```

---

## Add a New Trigger

### 5-Step Recipe

**Step 1 — Define the trigger in the right JSON file**

Pick the correct config file:

| If the trigger is... | Add it to... |
|---|---|
| Generic (works in any app) | `templates/triggers.json` → `triggers` object |
| Obsidian-only (`/doc`, `/link`, `/summarize`, `/daily`, `/atomic`, `/moc`) | `templates/obsidian.triggers.json` → `triggers` object |
| Gmail-only (`/reply`, `/draft`) | `templates/gmail.triggers.json` → `triggers` object |
| WhatsApp-only (`/reply`, `/shorten`, `/translate`) | `templates/whatsapp.triggers.json` → `triggers` object |

**Step 2 — Write the trigger JSON**

```json
"/mytrigger": {
  "pipeline": "cot",
  "provider": "local",
  "system_template": "You are an AI assistant. Respond to: {{user_input}}",
  "user_template": "{{user_input}}",
  "temperature": 0.3,
  "max_tokens": 512,
  "output_mode": "inline"
}
```

Required fields:

| Field | Type | Description |
|-------|------|-------------|
| `pipeline` | `"cot"` or `"tot"` | Reasoning pipeline. `"tot"` = Tree-of-Thought (multi-branch, uses strip mode) |
| `provider` | string | Default provider ID from `providers` section |
| `system_template` | string | System prompt with `{{variable}}` substitution |
| `user_template` | string | User prompt template |
| `temperature` | float (0–2) | LLM temperature |
| `max_tokens` | int | Max output tokens |

Optional fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `fallback_provider` | string | — | Provider to try if primary fails |
| `extract_pattern` | string | — | Regex to extract final answer (e.g., `"FINAL:\\s*(.*)"`) |
| `branches` | int | 1 | Number of ToT branches (only for `pipeline: "tot"`) |
| `output_mode` | `"inline"` / `"strip"` / `"overlay"` | `"inline"` | How output is rendered |
| `skill_routing` | object | — | Voice auto-routing: `{"app": "obsidian", "skill": "mytrigger"}` |

**Step 3 — Add spoken alias (optional)**

If the trigger should be voice-accessible, add a mapping in `SpokenTriggerNormalizer.kt`:

```kotlin
// In the MAPPINGS list in SpokenTriggerNormalizer.kt
SpokenForm("slash mytrigger", "/mytrigger"),
```

**Step 4 — Run schema validation**

```bash
python3 -m json.tool templates/triggers.json > /dev/null && echo "OK"
```

Or use the bundled script:

```bash
python3 -c "
import json
with open('templates/triggers.json') as f:
    data = json.load(f)
t = data['triggers']['/mytrigger']
assert t['pipeline'] in ('cot', 'tot')
assert 0 <= t['temperature'] <= 2
assert t['max_tokens'] >= 1
print('Trigger validates OK')
"
```

**Step 5 — Write a unit test for the trigger routing rule**

If the trigger needs special routing logic (e.g., routing to a specific provider), add a test in `RuleParserTest.kt`:

```kotlin
@Test
fun `mytrigger routes to anthropic when local is unreachable`() {
    val expr = parser.parse("trigger.pipeline == 'cot' && provider.local.unreachable")
    val context = RuleContext(
        triggerPipeline = "cot",
        providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = true)),
    )
    assertTrue("mytrigger should use fallback provider", parser.evaluate(expr, context))
}
```

---

## Add a New Provider

### 7-Step Recipe

**Step 1 — Create the provider implementation**

Create `app/src/main/java/dev/patrickgold/florisboard/ime/ai/providers/YourProvider.kt`:

```kotlin
package dev.patrickgold.florisboard.ime.ai.providers

import kotlinx.coroutines.flow.Flow

class YourProvider(
    override val config: ProviderConfig,
) : Provider {
    override val id = "your_provider_id"
    override val displayName = "Your Provider Name"

    override fun complete(request: CompletionRequest): Flow<Token> {
        // Implement streaming completion using the provider's API
        // Return Flow<Token> — emit Token for each chunk, finish with
        // Token(text = "", finishReason = FinishReason.STOP)
        TODO("Implement API call")
    }
}
```

**Step 2 — Implement the `complete()` method**

Follow the pattern from existing providers:

| Provider | Pattern | File |
|----------|---------|------|
| OpenAI-compatible | SSE streaming via OkHttp | `OpenAIProvider.kt`, `DeepseekProvider.kt` |
| Anthropic | SSE streaming via OkHttp | `AnthropicProvider.kt` |
| Gemini | Streaming via OkHttp | `GeminiProvider.kt` |
| Local llama.cpp | OpenAI-compatible on localhost | `LlamaCppLocal.kt` |

Key pattern:

```kotlin
override fun complete(request: CompletionRequest): Flow<Token> = callbackFlow {
    val client = OkHttpClient.Builder()
        .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    val jsonBody = buildJsonObject {
        put("model", config.model ?: "default")
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", request.system) }
            addJsonObject { put("role", "user"); put("content", request.user) }
        }
        put("temperature", request.temperature)
        put("max_tokens", request.maxTokens)
        put("stream", true)
    }

    val request_body = RequestBody.create(MediaType.parse("application/json"), jsonBody.toString())
    val httpRequest = Request.Builder().url(config.url).post(request_body).build()

    try {
        val response = client.newCall(httpRequest).execute()
        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") {
                        trySend(Token("", FinishReason.STOP))
                    } else {
                        // Parse JSON and extract text delta
                        val json = Json.parseToJsonElement(data).jsonObject
                        val delta = json["choices"]?.jsonArray?.get(0)?.jsonObject
                            ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                        if (delta.isNotEmpty()) trySend(Token(delta))
                    }
                }
            }
        }
    } catch (e: Exception) {
        trySend(Token("", FinishReason.ERROR))
    }
    close()
}
```

**Step 3 — Add the provider to the health tracker (in integration code)**

```kotlin
val healthTracker = HealthTracker()
val yourProvider = YourProvider(config)
// healthTracker is updated automatically when ProviderRouter calls the provider
```

**Step 4 — Register the provider in `ProviderRouter`**

If the provider should be discoverable via routing rules, add it in the provider routing logic:

```kotlin
// In the provider selection logic
val providers = mapOf(
    "local" to llamaCppProvider,
    "anthropic" to anthropicProvider,
    "your_provider_id" to yourProvider,
    // ...
)
```

**Step 5 — Add API key handling (if needed)**

If your provider needs an API key, add the key reference to `KeyVault`:

```kotlin
// In KeyVault usage
val apiKey = keyVault.getKey("YOUR_PROVIDER_KEY")
```

And in the provider, retrieve it via `config.keyRef`:

```kotlin
val apiKey = request.apiKey
```

**Step 6 — Add the provider config to `templates/triggers.json`**

```json
"providers": {
  "your_provider_id": {
    "url": "https://api.yourprovider.com/v1/chat/completions",
    "model": "your-model-name",
    "keyRef": "YOUR_PROVIDER_KEY",
    "priority": 5,
    "maxTokens": 4096,
    "timeoutMs": 30000
  }
}
```

**Step 7 — Add routing rules and test them**

Add routing rules in the `routing.rules` array:

```json
{
  "if": "trigger.pipeline == 'tot' && trigger.maxTokens > 2048",
  "use": "your_provider_id"
}
```

Then add evaluation tests in `RuleParserTest.kt`:

```kotlin
@Test
fun `high-token tot routes to your_provider_id`() {
    val expr = parser.parse("trigger.pipeline == 'tot' && trigger.maxTokens > 2048")
    val context = RuleContext(triggerPipeline = "tot", triggerMaxTokens = 3072)
    assertTrue("Should match routing rule", parser.evaluate(expr, context))
}
```

---

## Add a New Context Variable

### 3-Step Recipe

Context variables like `{{vault.name}}`, `{{file.path}}`, and `{{user_input}}` are resolved during trigger processing. To add a new one:

**Step 1 — Add the variable to the JSON template**

Use `{{your_variable}}` in any `system_template` or `user_template` string:

```json
"system_template": "Current project: {{project.name}}. User query: {{user_input}}"
```

**Step 2 — Add resolution in `AppContext` (if it's a context variable)**

If the variable comes from app context (e.g., Obsidian vault property), add a field to `AppContext`:

```kotlin
// In ContextResolver.kt
data class AppContext(
    val vaultName: String? = null,
    val filePath: String? = null,
    val projectName: String? = null,  // NEW
    val windowTitle: String? = null,
)
```

Then populate it from the accessibility node or window title:

```kotlin
// In ContextResolver.resolveFromTitle()
return AppContext(
    vaultName = match.groupValues[1].trim(),
    filePath = match.groupValues[2].trim(),
    projectName = extractProjectName(match.groupValues[2].trim()),
    windowTitle = title,
)
```

**Step 3 — Wire it into CTE (Context Template Engine) substitution**

The context variables are substituted during template resolution (where `ResolvedPrompt` is built). Add the substitution in the template resolver:

```kotlin
// In the template substitution logic (e.g., in bridges/)
fun resolveTemplate(template: String, context: AppContext, userInput: String): String {
    return template
        .replace("{{vault.name}}", context.vaultName ?: "")
        .replace("{{file.path}}", context.filePath ?: "")
        .replace("{{project.name}}", context.projectName ?: "")  // NEW
        .replace("{{user_input}}", userInput)
}
```

Then add a test in the appropriate test file:

```kotlin
@Test
fun `template substitution resolves project name`() {
    val context = AppContext(projectName = "MyProject")
    val result = resolveTemplate("Project: {{project.name}}.", context, "")
    assertEquals("Project: MyProject.", result)
}
```
