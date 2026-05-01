# agentA-Z Keyboard — Task List
**Last updated:** 2026-05-01  
**Branch:** main | **HEAD:** b9e3f377

---

## ✅ COMPLETED THIS SESSION (2026-05-01)

### Markwon Markdown Rendering in OverlayRenderer — DONE ✅
**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `OverlayRenderer.kt`
- Added Markwon 4.6.2 (core + strikethrough + tables) to version catalog and build deps
- Replaced plain text fallback (`textView.text = text`) with full `markwon.setMarkdown(textView, text)`
- Custom theme plugin: code blocks get monospace + `surfaceContainerHighest` background, links use primary color
- All colors pulled from Material3 `colorScheme` via `toArgb()` — theme-aware (dark mode compatible)
- Markwon instance `remember`'d keyed on theme colors — no rebuild on every recomposition

### Inline Cancellation Feedback — DONE ✅
**Files:** `InlineRenderer.kt`, `CteEngine.kt`
- Added `onCancelled: ((tokensCommitted: Int) -> Unit)?` callback to InlineRenderer constructor
- Added `tokensCommitted` counter, incremented per token commit
- Callback fires in `onKeyEvent()` when user cancels via keypress
- CteEngine wires Toast: "Generation stopped — N tokens kept" or "Generation cancelled"

---

## ✅ COMPLETED PREVIOUS SESSION (2026-04-28)

### TASK-001: Rebuild APK + reinstall on device — DONE ✅
**What was done:**
- Installed Eclipse Temurin JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`)
- **BUILD SUCCESSFUL** in 1m 9s — 137 actionable tasks, 0 errors
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` → **Success**
- `adb reboot` → device clean boot
- Logcat confirms **no FATAL crash** — IME loads cleanly at boot

### TASK-002: Wire ACTION_USER_UNLOCKED BroadcastReceiver — DONE ✅
**File:** `app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt`
- Added `UnlockReceiver` inner class (`BroadcastReceiver`)
- Registers for `Intent.ACTION_USER_UNLOCKED` in `onCreate()`
- Unregisters in `onDestroy()`
- Calls `KeyVault.getInstance(context).onUserUnlocked()` on receipt

### TASK-006: Fix testLocalConnection() in CteSettingsActivity — DONE ✅
**File:** `app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteSettingsActivity.kt`
- Implemented HTTP GET to `http://127.0.0.1:8080/health` on `Dispatchers.IO`
- 3-second connect/read timeout, Toast response on `Dispatchers.Main`
- Function changed from `private fun` to `private suspend fun`

---

## 🟡 HIGH — Needs Phone Interaction

### TASK-003: Set API keys for Groq + Cerebras on device
**Why:** GROQ_KEY and CEREBRAS_KEY are not set in KeyVault.

**Note:** CTE settings are NOT in the main settings menu — only accessible via deep link. Try:
1. **Browser:** Open `ui://florisboard/cte` on phone
2. **ADB:**
   ```bash
   adb shell am start -n dev.patrickgold.florisboard.vault.debug/dev.patrickgold.florisboard.ime.ai.CteKeysActivity
   ```
3. Tap **"Manage API Keys"** → paste `GROQ_KEY` and `CEREBRAS_KEY`

Keys ready — free credits:
- **Groq:** https://console.groq.com (no credit card)
- **Cerebras:** https://cloud.cerebras.ai (no credit card, 1M tokens/day)

---

### TASK-004: Test /fix trigger end-to-end
After keys set + llama-server running:
1. Type: `This sentense has a error /fix`
2. Check logcat: `adb logcat -s CTE CteEngine ProviderRouter`

---

### TASK-005: Start llama-server in Termux for local provider
**Model confirmed on device:** `/data/data/com.termux/files/home/LANE/llama.cpp/models/Qwen3.5-2B-Q4_K_M.gguf`

**Note:** The `LANE` directory contains the termux-obsidian-claude-vault stack (llama.cpp, whisper.cpp, mlc-llm, open-interpreter).

**Preferred approach (Option A — update triggers.json):**
1. Pull current triggers.json from device
2. Change `"model": "gemma-3n-e2b"` to correct Qwen model field
3. Push back to device
4. Copy model to shared storage: `cp ~/LANE/llama.cpp/models/Qwen3.5-2B-Q4_K_M.gguf ~/storage/shared/models/`
5. In Termux: `llama-server -m ~/storage/shared/models/Qwen3.5-2B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 --ctx-size 2048 -ngl 0`

---

## 🟢 MEDIUM — Next Sprint

### ~~TASK-007: Add CTE/AI entry to main settings menu~~ — ✅ ALREADY EXISTS
**Status:** Entry exists at `HomeScreen.kt:158` — launches `CteSettingsActivity`. Confirmed visible on device 2026-05-01.

### TASK-008: Storage audit + model pruning strategy
**Device storage:** ~453/477 GB used. Adding new models will be tight.

**Decisions needed:**
- Keep or remove older Qwen versions if newer one works well
- Evaluate if Gemma 3B Q4 would fit and outperform Qwen 2B for keyboard tasks
- Consider symlink from Termux `~` to shared storage for model management

### TASK-009: Add DeepSeek provider impl
**Why:** `triggers.json` has `deepseek` in provider ladder (priority 9, off) but `CteEngine.buildPipeline()` doesn't have a case for it — falls to `else` branch.

**File:** `providers/DeepseekProvider.kt` — already exists, just needs wiring in `CteEngine.buildPipeline()`:
```kotlin
"deepseek" -> if (apiKey != null) DeepseekProvider(config, apiKey) else null
```

### TASK-010: Gemma local model — download + test
**Gemma 2B/3B Q4 GGUF** would be consistent with Groq's `gemma2-9b-it` fallback. Steps:
1. Check device free space first
2. Pull via Termux wget
3. Test with llama-server
4. Update `local` provider model ref in triggers.json

---

### TASK-010: CoT/ToT pipeline integration test
**Why:** The `<<cot>>` and `<<tot>>` triggers exist in config but haven't been tested since Anthropic (FINISHER role) was added. Need to verify FINISHER gate works correctly.

**Test:** Type `This needs deep analysis <<tot>>` → confirm Anthropic is selected (not groq/cerebras) → verify multi-step reasoning output.

---

## 📋 BACKLOG

- **TASK-011:** Settings UI — add provider health status indicators (last ping, error rate)
- **TASK-012:** Strip renderer — implement suggestion strip output mode for short completions
- **TASK-013:** Overlay renderer — test skills.json integration
- **TASK-014:** Rate limit tracking — implement in-memory RPM counter per provider so router can skip before getting 429s
- **TASK-015:** User-facing trigger editor — let users add/edit triggers from Settings UI without editing JSON directly

---

## Notes for Claude Code Session

- ADB path: `C:\Users\MarsBase\Android\Sdk\platform-tools\adb.exe`
- Device package (debug): `dev.patrickgold.florisboard.vault.debug`
- App data dir: `/data/data/dev.patrickgold.florisboard.vault.debug/files/cte/configs/`
- Config push pattern: push to `/data/local/tmp/` then `run-as` copy (sdcard → /data/local/tmp is not readable by run-as)
- git large push: `git config http.postBuffer 524288000` + detached PowerShell background process
- Git remote: `https://github.com/Gsunny45/android-ai-keyboard-harness.git`
