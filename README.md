# android-ai-keyboard-harness (agentA-Z)

> **Android AI Keyboard Orchestration System — Typeless + CleverType + Templater + Local LLM**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Current Status (2026-04-28)

| Status | Item |
|--------|------|
| ✅ | **APK built + installed** — KeyVault boot crash fixed, device reboots cleanly |
| ✅ | **ACTION_USER_UNLOCKED receiver** wired in FlorisImeService |
| ✅ | **9-provider ladder** configured: local, groq, cerebras, anthropic, openai, gemini_1, gemini_2, openrouter, deepseek |
| ✅ | **Provider toggle + role system** (primary/finisher/fallback) in Settings UI |
| ✅ | **testLocalConnection()** implemented (HTTP health check to llama-server) |
| ⚠️ | **CTE settings hidden** — no entry in main settings menu; access via deep link only (`ui://florisboard/cte`) |
| ❌ | **Voice IME** — not installed. Whisper-to-input fork at [Gsunny45/whisper-to-input](https://github.com/Gsunny45/whisper-to-input) needs integration |
| ❌ | **API keys unset** — Groq + Cerebras keys ready but not entered in KeyVault |
| ❌ | **llama-server not running** — Qwen model on device (`/data/data/com.termux/files/home/LANE/llama.cpp/models/Qwen3.5-2B-Q4_K_M.gguf`) |

---

## Vision

An **orchestrator-first, JSON-driven, open-source Android IME** that fuses:

- **Voice polish** (Typeless-style): whisper → raw text → LLM cleanup pipeline
- **Contextual AI** (CleverType-style): app-aware suggestions, per-app personas
- **Template triggers** (Obsidian Templater-style): `/formal`, `<<cot>>`, `/meta`, etc.
- **Local/cloud LLM inference**: Qwen2.5-Coder on-device via llama.cpp or cloud API

The orchestrator is the product. A JSON trigger layer sits between keystrokes and any AI provider, routing text through configurable reasoning pipelines — runnable fully offline on a mid-range Android phone.

---

## Two Design Paths

### Design 1 — Cloud-Hybrid (Claude API + MCP)

Uses the Claude API and MCP servers as the primary AI backend, with local Qwen as fallback. Best for power users with cloud budget who want maximum capability.

### Design 2 — Fully Local (GGUF On-Device)

Runs entirely on-device using llama.cpp or MLC-LLM. No data leaves the phone. Best for privacy, no-data-plan use, and full offline operation.

Both paths share the same trigger/orchestration layer — switch providers by changing `defaultEndpoint` in `templates/triggers.json`.

---

## Architecture Overview (5 Layers)

```
┌─────────────────────────────────────────────────────┐
│  Layer 1: INPUT                                     │
│  Physical tap · Voice (Whisper STT) · Clipboard     │
├─────────────────────────────────────────────────────┤
│  Layer 2: ORCHESTRATION                             │
│  Trigger parser · App profile · Persona selector    │
├─────────────────────────────────────────────────────┤
│  Layer 3: REASONING                                 │
│  Single-shot · CoT <<cot>> · ToT <<tot>> · /fix     │
├─────────────────────────────────────────────────────┤
│  Layer 4: AI PROVIDERS                              │
│  Local llama.cpp :8080  ·  Cloud APIs (9 providers)  │
├─────────────────────────────────────────────────────┤
│  Layer 5: OUTPUT                                    │
│  Inline insert · Suggestion strip · Overlay sheet   │
└─────────────────────────────────────────────────────┘
```

See [`docs/architecture.md`](docs/architecture.md) for full Mermaid diagrams (Design 1 + Design 2).

---

## AI Provider Ladder (Priority Order)

| Priority | Provider | Model | Role | Free Tier |
|----------|----------|-------|------|-----------|
| 1 | **local** | Qwen3.5-2B | PRIMARY | N/A (on-device) |
| 2 | **groq** | gemma2-9b-it | PRIMARY | 30 RPM, 14.4K req/day |
| 3 | **cerebras** | llama-3.3-70b | PRIMARY | 1M tokens/day, 30 RPM |
| 4 | **anthropic** | claude-haiku-4-5-20251001 | FINISHER | No |
| 5 | **openai** | gpt-4o-mini | FINISHER (off) | No |
| 6 | **gemini_1** | gemini-2.0-flash | FALLBACK | 1M tokens/day |
| 7 | **gemini_2** | gemini-2.0-flash | FALLBACK | 1M tokens/day |
| 8 | **openrouter** | llama-3.3-70b-instruct:free | FALLBACK (off) | 50 req/day free |
| 9 | **deepseek** | deepseek-chat | PRIMARY (off) | No |

Provider routing: `local.unreachable → groq → cerebras → gemini_1 → gemini_2`

---

## Current On-Device Model

| Model | Location | Size |
|-------|----------|------|
| Qwen3.5-2B-Q4_K_M.gguf | `/data/data/com.termux/files/home/LANE/llama.cpp/models/` | ~1.4 GB |

Note: The `LANE` directory also contains whisper.cpp, mlc-llm, and open-interpreter from the termux-obsidian-claude-vault stack.

---

## Build Environment Setup (Dev Machine)

### JDK (required for build)

```bash
winget install --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements
```

**Installed path:** `C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`

### ADB
**Path:** `C:\Users\MarsBase\Android\Sdk\platform-tools\adb.exe`

### Build & Install

```bash
cd android-ai-keyboard-harness
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

### Device Info

- **Model:** Moto G 5G 2022
- **ADB ID:** ZY22G7NFLK
- **Package:** `dev.patrickgold.florisboard.vault.debug`
- **App Data Dir:** `/data/data/dev.patrickgold.florisboard.vault.debug/files/cte/configs/`
- **Config push pattern:** `adb push` → `/data/local/tmp/` → `run-as` copy
- **Storage:** ~453/477 GB used

---

## Feature Icons & Provider Visual Identity

The settings UI (`CteSettingsActivity.kt`) shows a provider toggle list with:
- **Color-coded role badges** — PRIMARY (green), FINISHER (deep orange), FALLBACK (blue)
- **Provider name + role label** — displayed as uppercase monospace
- **Enabled/disabled dimming** — disabled cards at 50% alpha

**Desired enhancements for next session:**
- Per-provider feature icons (e.g., Groq lightning bolt, Cerebras chip, Anthropic logo)
- Health status indicators (last ping, online/offline)
- Provider model name display in toggle cards
- Streamlined API key entry flow

See [`app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteSettingsActivity.kt`](app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteSettingsActivity.kt) for the current Compose UI.

---

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/assets/cte_defaults/configs/triggers.json` | Source-of-truth 9-provider config with triggers |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteSettingsActivity.kt` | Settings UI — provider toggles, test connection, API keys |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/CteKeysActivity.kt` | API key management UI (AES-256-GCM encrypted) |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/providers/KeyVault.kt` | EncryptedSharedPreferences key storage |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/orchestration/CteEngine.kt` | Core orchestrator — trigger detection, pipeline build, provider routing |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/providers/ProviderRouter.kt` | Provider selection logic (health-aware, role-aware) |
| `app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt` | IME service — unlock receiver, CTE engine init |
| `app/src/main/java/dev/patrickgold/florisboard/ime/ai/settings/VoiceSettingsActivity.kt` | Voice trigger mapping UI (stub/broken — needs work) |
| `templates/triggers.json` | Orchestration config template |
| `scripts/termux-setup.sh` | Termux llama.cpp + Qwen setup script |
| `TASKS.md` | Full task breakdown with session history |

---

## Voice IME Status

**Not working. Not installed as a standalone app.** The whisper-to-input fork at [Gsunny45/whisper-to-input](https://github.com/Gsunny45/whisper-to-input) provides the reference voice → polish pipeline but needs:
1. APK build and install as a separate accessibility service
2. Integration with the CTE trigger layer (Layer 2)
3. Wiring into the `/voice` trigger in `triggers.json`

`VoiceSettingsActivity.kt` exists as a stub UI for configuring voice trigger mappings but requires the underlying whisper service to be operational.

---

## Phase Roadmap

### Phase 1 — SwiftSlate + llama.cpp Triggers
- [x] Repo structure + trigger schema
- [x] CTE settings UI (provider toggles, role badges, test connection)
- [x] KeyVault encrypted key storage
- [x] Key management activity (CteKeysActivity)
- [ ] Termux llama.cpp build + Qwen server — **model ready, server not running**
- [ ] Basic CoT pipeline working end-to-end

### Phase 2 — FlorisBoard Fork + Voice (3–4 weeks)
- [x] FlorisBoard fork with trigger interception layer
- [x] Per-app profiles (WhatsApp, Gmail, Obsidian)
- [ ] Voice input via whisper-to-input — **not started**
- [ ] Feature icons and provider visual identity in Settings UI
- [ ] CTE settings discoverability — add entry to main settings menu

### Phase 3 — CoT/ToT Routing + Personas (2 weeks)
- [x] 9 provider ladder with health-aware routing
- [x] Role system (primary/finisher/fallback)
- [ ] Full CoT/ToT routing with extract patterns — **untested**
- [ ] Multi-persona system with app-profile binding
- [ ] `/meta` skill builder for community triggers

### Phase 4 — Context Engine + RAG + App Store (2 weeks)
- [ ] SQLite-vec local embeddings + retrieval
- [ ] MLC-LLM GPU inference (OpenCL/Vulkan)
- [ ] AgentA-Z community skill/template marketplace

---

## Quick Start

### On Dev Machine
```bash
# Clone
git clone https://github.com/Gsunny45/android-ai-keyboard-harness.git
cd android-ai-keyboard-harness

# Build (JDK 17+ required)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
.\gradlew.bat assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

### On Android (Termux) — Start Local LLM
```bash
# Copy model to shared storage
cp ~/LANE/llama.cpp/models/Qwen3.5-2B-Q4_K_M.gguf ~/storage/shared/models/

# Start llama-server
llama-server \
  -m ~/storage/shared/models/Qwen3.5-2B-Q4_K_M.gguf \
  --host 127.0.0.1 \
  --port 8080 \
  --ctx-size 2048 \
  -ngl 0
```

### Access CTE Settings (hidden from main menu)
```bash
# Via ADB deep link:
adb shell am start -n dev.patrickgold.florisboard.vault.debug/dev.patrickgold.florisboard.ime.ai.CteKeysActivity

# Or open browser on phone to: ui://florisboard/cte
```

---

## Forked Repos

All upstream repos forked into `Gsunny45` for active development:

| Fork | Role | Status |
|------|------|--------|
| [Gsunny45/florisboard](https://github.com/Gsunny45/florisboard) | IME base keyboard | Integrated |
| [Gsunny45/SwiftSlate](https://github.com/Gsunny45/SwiftSlate) | Accessibility trigger layer | Reference |
| [Gsunny45/SmolChat-Android](https://github.com/Gsunny45/SmolChat-Android) | GGUF LLM runner | Reference |
| [Gsunny45/whisper-to-input](https://github.com/Gsunny45/whisper-to-input) | Voice IME | **Needs work** |
| [Gsunny45/ChatterUI](https://github.com/Gsunny45/ChatterUI) | llama.cpp frontend | Reference |
| [Gsunny45/textexpander_android](https://github.com/Gsunny45/textexpander_android) | Espanso trigger engine | Reference |
| [Gsunny45/KeyboardGPT](https://github.com/Gsunny45/KeyboardGPT) | Xposed LLM keyboard | Reference |
| [Gsunny45/mlc-llm](https://github.com/Gsunny45/mlc-llm) | GPU-accelerated inference | Reference |

---

## License

MIT — see [LICENSE](LICENSE).
