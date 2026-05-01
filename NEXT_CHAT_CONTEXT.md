# Next Chat Context — android-ai-keyboard-harness
_Updated 2026-05-01. Phone is USB-connected and ready._

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
  bridges/AccessibilityBridge.kt
  bridges/AppProfileManager.kt            ← currentLocale field
  CteKeysActivity.kt                      ← ✅ ADB key injection support
  CteSettingsActivity.kt                  ← ✅ wired to reloadCteConfig()
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

## 6. Priority Queue for Next Session

### ~~Markwon Markdown Rendering~~ — ✅ DONE (2026-05-01)
### ~~Inline Cancellation Feedback~~ — ✅ DONE (2026-05-01)

### ~~Priority 1 — UI: Add CTE/AI Entry to Main Settings Menu~~ — ✅ ALREADY DONE
**Status:** CTE entry exists in `HomeScreen.kt` line 158 — launches `CteSettingsActivity` directly. Confirmed visible on device.

### Priority 2 — Icons for CTE Features (Priority 4 from old context)
**Files:** `ComputingEvaluator.kt`, `KeyCode.kt`, `QuickAction.kt`
**Scope:** Add icon entries for CTE-related key codes (if any). Current `VOICE_INPUT` already has `Icons.Default.KeyboardVoice`. Check if any new key codes were added that lack icons.
**Note:** Revisit if there are new `KeyCode` constants without `computeImageVector()` entries.

### Priority 3 — Offline Voice: Vosk (Priority 1 from previous session)
Offline STT to replace/parallel Whisper API.
- Add Vosk SDK dep: `com.alphacephei:vosk-android:0.3.47`
- Download model on first-run to `filesDir/vosk/`
- Wire into `VoiceInputManager` as primary offline path
- Model target: `vosk-model-en-us-0.22-lgraph` (128MB)

### Priority 4 — Provider Health Indicators in Settings (TASK-011)
Add per-provider status badges to `CteSettingsActivity` (last ping, error rate, health status).

### Priority 5 — DeepSeek Provider Verification
DeepSeek provider exists at `providers/DeepseekProvider.kt` and is wired in `buildPipeline()`. Verify end-to-end if `DEEPSEEK_KEY` is set.

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
- There is NO "FlorisBoard Settings → CTE Keys" shortcut in the main settings screen — must deep-link or launch CteKeysActivity directly. **This is Priority 1 for next session.**
