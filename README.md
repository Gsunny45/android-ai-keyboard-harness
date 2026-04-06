# android-ai-keyboard-harness (agentA-Z)

> **Android AI Keyboard Orchestration System — Typeless + CleverType + Templater + Local LLM**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

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
│  Local llama.cpp :8080  ·  Cloud API (Claude etc.)  │
├─────────────────────────────────────────────────────┤
│  Layer 5: OUTPUT                                    │
│  Inline insert · Suggestion strip · Overlay sheet   │
└─────────────────────────────────────────────────────┘
```

See [`docs/architecture.md`](docs/architecture.md) for full Mermaid diagrams (Design 1 + Design 2).

---

## Default Local Model

**Qwen2.5-Coder-1.5B-Uncensored-DPO Q6_K GGUF**

| Spec | Value |
|---|---|
| Disk size | ~1.4 GB |
| RAM required | ~2 GB |
| Speed (Snapdragon 8 Gen 2 CPU) | 15–25 tok/s |
| Context window | 4096 tokens |
| Quantization | Q6_K (GGUF) |

Excellent balance of speed, size, and instruction-following on Android hardware.

---

## Core Reference Repos

| Repo | License | Role |
|---|---|---|
| [SwiftSlate](https://github.com/swiftslate/swiftslate) | MIT | Trigger layer — Phase 1 keyboard base |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Apache-2.0 | IME base — Phase 2 deep integration |
| [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) | Apache-2.0 | GGUF runner on Android |
| [ChatterUI](https://github.com/Vali-98/ChatterUI) | — | llama.cpp frontend |
| [whisper-to-input](https://github.com/Alex-Annas/whisper-to-input) | GPLv3 | Voice → polish pipeline (voice IME) |
| [KeyboardGPT](https://github.com/Mcdado/KeyboardGPT) | — | Xposed LLM keyboard reference |
| [MLC-LLM](https://github.com/mlc-ai/mlc-llm) | — | GPU-accelerated on-device inference |
| [textexpander_android](https://github.com/arslansajid/textexpander_android) | GPLv3 | Espanso-style trigger reference |

---

## Phase Roadmap

### Phase 1 — SwiftSlate + llama.cpp Triggers (1–2 weeks)
- [x] Repo structure + trigger schema
- [ ] Termux llama.cpp build + Qwen server
- [ ] SwiftSlate trigger integration (custom endpoint → localhost:8080)
- [ ] Basic CoT pipeline working end-to-end

### Phase 2 — FlorisBoard Fork + Voice (3–4 weeks)
- [ ] FlorisBoard fork with trigger interception layer
- [ ] Per-app profiles (WhatsApp, Gmail, Obsidian)
- [ ] Voice input via whisper-to-input

### Phase 3 — CoT/ToT Routing + Personas (2 weeks)
- [ ] Full CoT/ToT routing with extract patterns
- [ ] Multi-persona system with app-profile binding
- [ ] `/meta` skill builder for community triggers

### Phase 4 — Context Engine + RAG + App Store (2 weeks)
- [ ] SQLite-vec local embeddings + retrieval
- [ ] MLC-LLM GPU inference (OpenCL/Vulkan)
- [ ] AgentA-Z community skill/template marketplace

---

## Quick Start

See [`docs/phase-1-quickstart.md`](docs/phase-1-quickstart.md) for the full Phase 1 setup guide.

```bash
# On Android in Termux:
pkg install git -y
git clone https://github.com/Gsunny45/android-ai-keyboard-harness ~/harness
bash ~/harness/scripts/termux-setup.sh
```

---

## Forked Repos

All upstream repos forked into `Gsunny45` for active development:

| Fork | Role |
|---|---|
| [Gsunny45/florisboard](https://github.com/Gsunny45/florisboard) | IME base keyboard |
| [Gsunny45/SwiftSlate](https://github.com/Gsunny45/SwiftSlate) | Accessibility trigger layer |
| [Gsunny45/SmolChat-Android](https://github.com/Gsunny45/SmolChat-Android) | GGUF LLM runner |
| [Gsunny45/whisper-to-input](https://github.com/Gsunny45/whisper-to-input) | Voice IME |
| [Gsunny45/ChatterUI](https://github.com/Gsunny45/ChatterUI) | llama.cpp frontend |
| [Gsunny45/textexpander_android](https://github.com/Gsunny45/textexpander_android) | Espanso trigger engine |
| [Gsunny45/KeyboardGPT](https://github.com/Gsunny45/KeyboardGPT) | Xposed LLM keyboard |
| [Gsunny45/mlc-llm](https://github.com/Gsunny45/mlc-llm) | GPU-accelerated inference |

---

## License

MIT — see [LICENSE](LICENSE).
