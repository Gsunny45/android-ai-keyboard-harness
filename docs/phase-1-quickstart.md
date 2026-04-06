# Phase 1 Quickstart: SwiftSlate + Termux + llama.cpp

Get a working AI keyboard pipeline on Android in ~30 minutes using local inference.

---

## Prerequisites

- Android phone with **Termux** installed (F-Droid recommended, not Play Store)
- **SwiftSlate** keyboard installed and set as default IME
- ~3 GB free storage (model + build artifacts)
- Stable Wi-Fi for initial downloads

---

## Step 1 — Install SwiftSlate

1. Download SwiftSlate from its GitHub releases page
2. Install the APK and grant IME permissions
3. Go to **Settings → System → Language & Input → On-screen keyboard**
4. Enable SwiftSlate and set it as the default keyboard

---

## Step 2 — Set Up Termux + llama.cpp

```bash
# In Termux:
termux-setup-storage
pkg install git -y
git clone https://github.com/Gsunny45/android-ai-keyboard-harness ~/harness
bash ~/harness/scripts/termux-setup.sh
```

The script installs `cmake`, `clang`, `make`, clones `llama.cpp`, and builds the server binary for arm64.

---

## Step 3 — Download the Qwen Model

```bash
# ~1.4 GB — use Wi-Fi
huggingface-cli download Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF \
  qwen2.5-coder-1.5b-instruct-q6_k.gguf \
  --local-dir ~/models
```

Or with curl:

```bash
curl -L -o ~/models/qwen2.5-coder-1.5b-instruct-q6_k.gguf \
  "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q6_k.gguf"
```

---

## Step 4 — Start the Inference Server

```bash
# Run in tmux so it persists across keyboard switches
pkg install tmux -y
tmux new-session -d -s llama \
  './llama.cpp/build/bin/llama-server -m ~/models/qwen2.5-coder-1.5b-instruct-q6_k.gguf -c 4096 -t 4 --host 127.0.0.1 --port 8080'

# Verify it's running
curl http://127.0.0.1:8080/health
# Expected: {"status":"ok"}
```

---

## Step 5 — Configure SwiftSlate Custom Endpoint

1. Open SwiftSlate → **Settings → AI / Snippets**
2. Set **Custom Endpoint** to: `http://127.0.0.1:8080/v1/chat/completions`
3. Import `templates/triggers.json` or add triggers manually:
   - `/formal` — rewrite in professional tone
   - `/casual` — rewrite in friendly tone
   - `/fix` — grammar + spelling fix
   - `/code` — natural language → code
   - `<<cot>>` — chain-of-thought reasoning

---

## Step 6 — Test Triggers

In any text field, type your text followed by a trigger:

```
hey can u send me that doc asap thx /formal
→ "Could you please share that document at your earliest convenience? Thank you."
```

```
write a python function that reverses a list /code
→ def reverse_list(lst): return lst[::-1]
```

---

## Troubleshooting

| Issue | Fix |
|---|---|
| Build fails | `pkg reinstall clang cmake` then retry |
| Server won't start | Check model path; verify ~2 GB RAM free (`free -h`) |
| Slow responses | Reduce `-c 4096` to `-c 2048`; close background apps |
| SwiftSlate can't reach server | Confirm Termux is running; test with `curl` first |

---

## Next Steps (Phase 2)

- Fork FlorisBoard for deep trigger interception at IME level
- Enable per-app profiles (WhatsApp, Gmail, Obsidian) via `templates/per-app-profiles/`
- Add voice input via `whisper-to-input` for voice → polish pipeline
