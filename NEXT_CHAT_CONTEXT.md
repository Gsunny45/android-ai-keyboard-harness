# Next Chat Context — android-ai-keyboard-harness
_Generated 2026-05-02. Phone is USB-connected and ready._

---

## Project: FlorisBoard fork with CTE (Context-aware Type Engine) AI layer

**Package:** `dev.patrickgold.florisboard` (debug: `dev.patrickgold.florisboard.vault.debug`)
**Source layout:** Kotlin code in `app/src/main/kotlin/`, AI-layer Java in `app/src/main/java/`
**Build:** Gradle + KSP, AGP, Compose UI
**ADB path:** `C:\Users\MarsBase\Android\Sdk\platform-tools\adb.exe` (in user PATH)
**Device:** Moto g 5G (2022), Android 13 (API 33), serial `ZY22G7NFLK`

---

## 1. Architecture Overview

```
FlorisImeService (Kotlin)
  ├── CteEngine              ← ai/orchestration/CteEngine.kt
  │     ├── TriggerConfigStore  ← reads/seeds assets/cte_defaults/ → filesDir/cte/
  │     │     └── reloadConfig()  ✅ NOW VALIDATES JSON + clears engine caches
  │     ├── ProviderRouter      ← health-aware, role/budget routing
  │     │     ├── Budgets now read from routing.json (not hardcoded) ✅
  │     │     ├── Routing rules now parsed from routing.json (not emptyList) ✅
  │     │     └── Provider impls: Anthropic, OpenAI, Groq, Cerebras,
  │     │                         OpenRouter, Deepseek, Gemini, LlamaCppLocal
  │     ├── KeyVault            ← EncryptedSharedPreferences API keys
  │     │     └── ADB inject: CteKeysActivity?inject=1&keyRef=X&keyValue=Y ✅
  │     └── OutputModeRouter    → InlineRenderer / StripRenderer / OverlayRenderer
  │
  ├── VoiceInputManager      ← ai/voice/VoiceInputManager.kt ✅ REWRITTEN prev session
  │     ├── Primary: AudioRecord → WAV → OpenAI whisper-1 API
  │     ├── Fallback: Android SpeechRecognizer (if no OPENAI_KEY)
  │     ├── Auto-stops on 1.5s silence after 400ms+ speech
  │     └── processedOutput StateFlow → FlorisImeService → editorInstance.commitText()
  │
  ├── PermissionTrampolineActivity  ← NEW ✅ Transparency for RECORD_AUDIO runtime request
  │     ├── registerForActivityResult(ActivityResultContracts.RequestPermission)
  │     ├── Broadcasts ACTION_VOICE_PERMISSION_GRANTED/DENIED
  │     └── FlorisImeService.PermissionReceiver listens + retries recording
  │
  └── NlpManager (Kotlin)    ← standard FlorisBoard suggestion/spell pipeline
```

**Config tree (on-device):** `filesDir/cte/configs/triggers.json`, `routing.json`, `skills.json`, `personas.json`
**Bundled defaults:** `app/src/main/assets/cte_defaults/`

---

## 2a. What Was Done (2026-05-01)

### ✅ Markwon Markdown Rendering
**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `OverlayRenderer.kt`

| Component | Change |
|---|---|
| `libs.versions.toml` | Added `markwon = "4.6.2"` + 3 library entries (core, strikethrough, tables) |
| `build.gradle.kts` | Added `markwon-core`, `markwon-ext-strikethrough`, `markwon-ext-tables` deps |
| `OverlayRenderer.MarkdownContent()` | Replaced `textView.text = text` with `markwon.setMarkdown(textView, text)` using themed Markwon builder |
| Theme integration | Code blocks: monospace + `surfaceContainerHighest` bg. Links: primary color. All via Material3 `colorScheme.toArgb()` |

### ✅ Inline Cancellation Feedback
**Files:** `InlineRenderer.kt`, `CteEngine.kt`

| Component | Change |
|---|---|
| `InlineRenderer` | Added `onCancelled` callback param + `tokensCommitted` counter. Fires in `onKeyEvent()` on cancel |
| `CteEngine.buildPipeline()` | Wires Toast: "Generation stopped — N tokens kept" or "Generation cancelled" |

---

## 2b. What Was Done (2026-04-29)

### ✅ Priority 3a — TriggerConfigStore.reloadConfig() Fixed
**Files:** `TriggerConfigStore.kt`, `CteEngine.kt`, `FlorisImeService.kt`, `CteSettingsActivity.kt`

| Component | Change |
|---|---|
| `CteEngine.reloadConfig()` | New method — clears `registeredTriggers`, `pipeline`, `pipelineInitAttempted` caches so next trigger re-reads configs from disk |
| `FlorisImeService.reloadCteConfig()` | New companion method — accesses engine via `WeakReference` singleton |
| `CteSettingsActivity.reloadConfig()` | Now calls **both** `TriggerConfigStore.reloadConfig()` AND `FlorisImeService.reloadCteConfig()` |
| `TriggerConfigStore.reloadConfig()` | Replaced stub with real validation — checks `triggers.json` + `routing.json` exist and are parseable JSON |

### ✅ Priority 3b — ProviderRouter Budget Map Reads from routing.json
**Files:** `routing.json`, `ProviderRouter.kt`, `CteEngine.kt`

| Component | Change |
|---|---|
| `routing.json` | Added `"budgets"` section: `cheap`→`[deepseek,local,gemini_1]`, `balanced`→`[]`, `premium`→`[anthropic,openai]` |
| `RoutingConfig` | Added `budgets: Map<String, List<String>>` field |
| `ProviderRouter.selectProviderId()` | Removed hardcoded `budgetMap` — reads from `routingConfig.budgets` instead |
| `CteEngine.buildPipeline()` | Now **actually reads** `routing.json` for rules + budgets (was reading from `triggers.json` with `rules = emptyList()`) |

### ✅ Priority 2 — RECORD_AUDIO Runtime Permission Trampoline
**Files:** `PermissionTrampolineActivity.kt` (NEW), `FlorisImeService.kt`, `AndroidManifest.xml`

| Component | Change |
|---|---|
| `PermissionTrampolineActivity.kt` | New transparent activity using `registerForActivityResult` + `ActivityResultContracts.RequestPermission`. Broadcasts `GRANTED`/`DENIED` to IME |
| `FlorisImeService.startVoiceInput()` | Now checks `RECORD_AUDIO` before recording. If not granted, launches trampoline via `Intent.FLAG_ACTIVITY_NEW_TASK` |
| `PermissionReceiver` | New inner class — listens for broadcast result. On GRANTED: retries recording. On DENIED: shows toast |
| `AndroidManifest.xml` | Registered trampoline with `@style/FlorisAppTheme.Transparent` + `excludeFromRecents=true` |

### ✅ ADB Key Injection
**File:** `CteKeysActivity.kt`

Added intent-driven key injection for automation:
```powershell
& $adb shell am start -n dev.patrickgold.florisboard.vault.debug/ \
    dev.patrickgold.florisboard.ime.ai.CteKeysActivity \
    --ei inject 1 -e keyRef GROQ_KEY -e keyValue "<actual_key>"
```
Injects directly to `EncryptedSharedPreferences` via `KeyVault.set()`. Activity auto-finishes.

### ✅ DeepSeek Provider (TASK-009)
Already wired in `CteEngine.buildPipeline()` — confirmed, no changes needed.

---

## 2c. What Was Done (2026-05-02)

### ✅ Obsidian Bridge Wired — Template Variable Resolution
**Files:** [`ObsidianBridge.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/bridges/ObsidianBridge.kt), [`CteEngine.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/orchestration/CteEngine.kt), [`FlorisImeService.kt`](app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt), [`CteSettingsActivity.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteSettingsActivity.kt), [`ContextResolver.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/orchestration/ContextResolver.kt), [`obsidian.triggers.json`](app/src/main/assets/cte_defaults/profiles/obsidian.triggers.json)

| Component | Change |
|---|---|
| `ObsidianBridge` | Fixed `===` reference identity bug in `extractFrontmatter()`. Added explicit `StandardCharsets.UTF_8`. Added `setVaultName()`/`getVaultName()` config methods (persisted via SharedPreferences). Made `OBSIDIAN_TITLE` regex public. Simplified `navigateToFile()`. |
| `ContextResolver` | Removed duplicated `OBSIDIAN_TITLE` regex — now references `ObsidianBridge.OBSIDIAN_TITLE` |
| `CteEngine` | Added `obsidianBridge` + `appProfileManager` constructor params. New `resolveTemplateVariables()` method: resolves `{{vault.name}}`, `{{file.path}}`, `{{file.tags}}`, `{{system.time.iso}}`, `{{system.tz}}`, strips `{{#each file.tags}}` blocks gracefully, preserves `{{user_input}}`. Wired into `onSelectionChanged()` pipeline. |
| `FlorisImeService` | Instantiates `ObsidianBridge` in `onCreate()`. Passes to `CteEngine` constructor. |
| `CteSettingsActivity` | Added "Obsidian Vault" section in General — `OutlinedTextField` for vault name + "Save Vault Name" button. Persists via `ObsidianBridge.setVaultName()`. |
| `obsidian.triggers.json` | Updated system prompts to read naturally when template variables resolve to empty (no window title yet without AccessibilityService) |

### ✅ Full Repo Exploration
**Completed:** Catalog of all 33 `.kt` files in the AI layer, 18 assets files, and all Kotlin-side integration points. Findings:
- **[`VoiceSettingsActivity.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/settings/VoiceSettingsActivity.kt)** — Fully implemented Compose UI for spoken-trigger mappings, but **unconnected** from runtime `SpokenTriggerNormalizer`
- **`profiles/gmail.triggers.json`** + **`profiles/whatsapp.triggers.json`** — Fully specified per-app profiles, **not loaded** by CteEngine (no per-app trigger routing)
- **Design artifacts** — `plugins/`, `evals/`, `docs/`, `scripts/` directories are forward-planning documents only (PluginLoader, EvalRunner, SkillEngine don't exist in code)
- **Three major architectural gaps identified:**
  1. **✅ NOW FIXED:** Template variable resolution didn't exist — CteEngine passed `{{vault.name}}` raw to providers
  2. **🔲 STILL OPEN:** App profiles not wired — `triggers.json` declares `appProfiles` but CteEngine never loads per-app overrides
  3. **🔲 STILL OPEN:** Bridge layer is dead code for window titles — `AccessibilityBridge.resolveWindowTitleFromAccessibility()` always returns null (no AccessibilityService in manifest)

### ✅ Obsidian Plugin Ecosystem Analysis — OPTIONS.md
**New file:** [`OPTIONS.md`](OPTIONS.md) — catalog analysis of 38+ Obsidian plugins. Core insight: phone-only integration = convention alignment (keyboard output format matching what plugins expect to read). Templater tokens are literal text — no escaping needed, just prompt engineering.

---

## 3. Key Files — Exact Paths

```
# AI layer (Java)
app/src/main/java/dev/patrickgold/florisboard/ime/ai/
  orchestration/CteEngine.kt              ← ✅ reloadConfig() + proper routing.json parsing
  orchestration/ContextResolver.kt
  orchestration/LlamaServerService.kt
  providers/Provider.kt
  providers/ProviderRouter.kt             ← ✅ budgets from routing.json
  providers/KeyVault.kt                   ← getKey("OPENAI_KEY") for voice
  providers/AnthropicProvider.kt
  providers/OpenAIProvider.kt
  providers/GroqProvider.kt
  providers/CerebrasProvider.kt
  providers/OpenRouterProvider.kt
  providers/DeepseekProvider.kt
  providers/GeminiProvider.kt
  providers/LlamaCppLocal.kt
  providers/HealthTracker.kt
  providers/CostLogger.kt
  providers/RuleExpr.kt / RuleParser.kt
  voice/VoiceInputManager.kt              ← AudioRecord + Whisper API ✅
  voice/SpokenTriggerNormalizer.kt
  voice/WaveformStripRenderer.kt
  trigger/TriggerConfigStore.kt           ← ✅ reloadConfig() validates JSON
  trigger/TriggerParser.kt
  output/InlineRenderer.kt
  output/OutputModeRouter.kt
  output/OverlayRenderer.kt
  output/StripRenderer.kt
  bridges/AccessibilityBridge.kt          ← ⬜ windowTitle always null (no AccessibilityService)
  bridges/AppProfileManager.kt            ← currentLocale + currentPackageId
  bridges/ObsidianBridge.kt               ← ✅ WIRED into CteEngine + vault name config
  CteKeysActivity.kt                      ← ✅ ADB key injection support
  CteSettingsActivity.kt                  ← ✅ reloadCteConfig() + vault name setting
  PermissionTrampolineActivity.kt         ← ✅ NEW — runtime RECORD_AUDIO request

# Core IME (Kotlin)
app/src/main/kotlin/dev/patrickgold/florisboard/
  FlorisImeService.kt                     ← ✅ VoiceInputManager + PermissionReceiver + reloadCteConfig()
  ime/keyboard/ComputingEvaluator.kt      ← ⬜ ICONS NEEDED (next session)
  ime/keyboard/KeyboardManager.kt         ← VOICE_INPUT → startVoiceInput()
  ime/text/key/KeyCode.kt
  ime/smartbar/quickaction/QuickAction.kt
  ime/smartbar/quickaction/QuickActionButton.kt

# Manifest
app/src/main/AndroidManifest.xml          ← ✅ RECORD_AUDIO + PermissionTrampolineActivity

# Config assets
app/src/main/assets/cte_defaults/configs/triggers.json
app/src/main/assets/cte_defaults/configs/routing.json   ← ✅ added "budgets" section
app/src/main/assets/cte_defaults/configs/skills.json
app/src/main/assets/cte_defaults/configs/personas.json
```

---

## 4. How to Enter API Keys

### Via ADB (Automated)
```powershell
$adb = "C:\Users\MarsBase\Android\Sdk\platform-tools\adb.exe"

# Single key
& $adb shell am start -n dev.patrickgold.florisboard.vault.debug/ \
    dev.patrickgold.florisboard.ime.ai.CteKeysActivity \
    --ei inject 1 -e keyRef GROQ_KEY -e keyValue "gsk_your_key_here"
```

### Via UI
1. Tap **Settings** key on FlorisBoard smartbar
2. Opens `FlorisAppActivity` → navigate to **CTE Settings** → **API Keys**
3. Tap **Set Key** next to each provider, paste key

---

## 5. Voice Input Flow (Current)

```
VOICE_INPUT key press
  → RECORD_AUDIO granted?
      YES → FlorisImeService.startVoiceInput()
      NO  → PermissionTrampolineActivity (transparent)
              → requestPermissionLauncher.launch(RECORD_AUDIO)
              → broadcast ACTION_VOICE_PERMISSION_GRANTED
              → FlorisImeService.PermissionReceiver retries

  → VoiceInputManager.startRecording()
      → OPENAI_KEY present?
          YES → AudioRecord 16kHz mono PCM → silence detection → WAV → Whisper API
          NO  → SpeechRecognizer fallback (Google online)
      → processTranscript() → _processedOutput.emit(text)
      → FlorisImeService collector → editorInstance.commitText()
```

---

## 6. Next Session — Start Here

### Priority: Wire App Profiles (highest ROI for remaining work)

The app profile system has everything configured but nothing wired:
- `appProfiles` section in `triggers.json` maps `md.obsidian` → `profiles/obsidian.triggers.json` (with 6 custom triggers), `com.google.android.gm` → `gmail.triggers.json`, `com.whatsapp` → `whatsapp.triggers.json`
- `AppProfileManager` tracks `currentPackageId` in `FlorisImeService` and is already passed to `CteEngine`
- `CteEngine.detectTrigger()` loads a flat trigger map — needs to merge per-app overrides

**Implementation sketch:**
1. `CteEngine.loadTriggersConfig()` → after loading base triggers, check `appProfileManager?.currentPackageId`
2. Look up `appProfiles[pkg]` in triggers.json, load that profile file
3. Merge `triggers_override` into the base trigger map (profile overrides take precedence)
4. Optionally apply the profile's `basePersona` for template variable resolution

### Suggested Next Steps (also open)
1. **VoiceSettingsActivity** — Connect the Compose UI to `SpokenTriggerNormalizer` so users can add spoken trigger mappings without code changes. Currently the UI is fully built but the normalizer uses hardcoded mappings.
2. **AccessibilityService** — Add a proper `AccessibilityService` to `AndroidManifest.xml` so `AccessibilityBridge.resolveWindowTitleFromAccessibility()` returns actual window titles. This unlocks `{{file.path}}` and `{{file.tags}}` resolution.
3. **Templater token prompting** — Update `obsidian.triggers.json` `/doc` and `/daily` system prompts to instruct the LLM to emit `<% tp.date.now() %>`, `{{title}}`, Dataview fields, and Tasks syntax. Tokens are literal text — no code changes needed in the render pipeline.

### Reference Documents
- [`bridges/ObsidianBridge.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/bridges/ObsidianBridge.kt) — ✅ NOW WIRED — SAF file reader + vault name config
- [`orchestration/ContextResolver.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/orchestration/ContextResolver.kt) — Obsidian window title parser (uses shared regex from ObsidianBridge)
- [`bridges/AppProfileManager.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/bridges/AppProfileManager.kt) — Tracks foreground app pkg (passed to CteEngine)
- [`bridges/AccessibilityBridge.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/bridges/AccessibilityBridge.kt) — ⬜ Window title always null
- [`profiles/obsidian.triggers.json`](app/src/main/assets/cte_defaults/profiles/obsidian.triggers.json) — 6 Obsidian-specific trigger overrides (not yet loaded by engine)
- [`profiles/gmail.triggers.json`](app/src/main/assets/cte_defaults/profiles/gmail.triggers.json) — 3 email-specific triggers (not yet loaded)
- [`profiles/whatsapp.triggers.json`](app/src/main/assets/cte_defaults/profiles/whatsapp.triggers.json) — 2 chat-specific triggers (not yet loaded)
- [`triggers.json`](app/src/main/assets/cte_defaults/configs/triggers.json) — Current trigger definitions (appProfiles section at bottom)
- [`settings/VoiceSettingsActivity.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/settings/VoiceSettingsActivity.kt) — Full UI, unconnected from normalizer
- [`OPTIONS.md`](OPTIONS.md) — Full Obsidian plugin integration analysis
- [`build.gradle.kts`](app/build.gradle.kts) — Dependency declarations

### Current Floor (what's already done and stable)
- ✅ Voice input (Whisper + SpeechRecognizer fallback)
- ✅ Markwon markdown rendering in overlay
- ✅ Inline cancellation feedback
- ✅ RECORD_AUDIO runtime permission trampoline
- ✅ Config reload from disk
- ✅ Routing rules + budgets from routing.json
- ✅ ADB key injection
- ✅ DeepSeek provider wired
- ✅ CTE settings accessible from main settings screen
- ✅ **Obsidian vault context wiring** (template variable resolution for `{{vault.name}}`, `{{system.time.iso}}`, `{{system.tz}}`)
- ✅ **Vault name config** in CTE settings UI
- ✅ **ObsidianBridge bug fixes** (reference identity bug, UTF-8 charset, deduplicated regex)

---

## 7. ADB Quick Commands

```powershell
$adb = "C:\Users\MarsBase\Android\Sdk\platform-tools\adb.exe"

# Build + install
.\gradlew installDebug

# Grant mic permission (needed after fresh install)
& $adb shell pm grant dev.patrickgold.florisboard.vault.debug android.permission.RECORD_AUDIO

# Inject API key
& $adb shell am start -n dev.patrickgold.florisboard.vault.debug/ \
    dev.patrickgold.florisboard.ime.ai.CteKeysActivity \
    --ei inject 1 -e keyRef GROQ_KEY -e keyValue "gsk_your_key_here"

# Watch CTE + voice logs
& $adb logcat -s CTE CteEngine ProviderRouter VoiceInputManager FlorisImeService -v time

# Check permissions
& $adb shell dumpsys package dev.patrickgold.florisboard.vault.debug | Select-String "RECORD_AUDIO"

# Launch CTE settings
& $adb shell am start -n dev.patrickgold.florisboard.vault.debug/dev.patrickgold.florisboard.ime.ai.CteSettingsActivity

# Launch API keys
& $adb shell am start -n dev.patrickgold.florisboard.vault.debug/dev.patrickgold.florisboard.ime.ai.CteKeysActivity
```

---

## 8. Key Gotchas

- AI source is in **`src/main/java`** (not `src/main/kotlin`) — don't confuse the split
- `VoiceInputManager` must be destroyed in `onDestroy()` — already wired
- `AudioRecord` must be released after use — handled in `startWhisperRecording()`
- `SpeechRecognizer` must be created/used on main thread — dispatched via `Dispatchers.Main`
- `KeyVault` defers until device unlock — `getKey()` returns null before first unlock
- `CteEngine.onSelectionChanged()` fires on every cursor move — 200ms debounce is critical
- `TriggerConfigStore.ensureDefaults()` must be called before config reads — called in `CteSettingsActivity.onCreate()`
- `CteEngine.reloadConfig()` only invalidates caches — the engine rebuilds lazily on next trigger (not immediately)
- `PermissionTrampolineActivity` uses `registerForActivityResult` — requires `ComponentActivity` (satisfied)
- Permission receiver broadcasts are **not** ordered / sticky — if IME is dead when trampoline fires, the grant is lost (acceptable for now)
- `RECORD_AUDIO` ADB grant persists across reinstalls on the **same device** — needs re-grant on fresh install or different device
- `buildPipeline()` now reads `routing.json` for rules + budgets — previously was parsing `triggers.json["routing"]` which didn't exist
- CTE entry exists in `HomeScreen.kt` — accessible from main Settings → CTE Settings
- `AccessibilityBridge.resolveWindowTitleFromAccessibility()` always returns null — no AccessibilityService declared in manifest. This means `{{file.path}}` and `{{file.tags}}` in system prompts resolve to empty strings.
- `ObsidianBridge.kt` — ✅ NOW WIRED into CteEngine with vault name config via SharedPreferences. SAF vault read path still needs a directory picker in the UI to be exercised.
- App profile triggers (`profiles/obsidian.triggers.json`, `gmail.triggers.json`, `whatsapp.triggers.json`) are defined and fully specified, but **no per-app trigger routing happens** — CteEngine loads a flat trigger map only. This is the next priority.
- `VoiceSettingsActivity.kt` — full Compose UI for spoken-trigger mappings, but the runtime `SpokenTriggerNormalizer` still uses hardcoded mappings. The UI edits are in-memory-only and never reach the normalizer.
