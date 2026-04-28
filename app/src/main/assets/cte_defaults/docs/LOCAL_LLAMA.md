# Local LLaMA Engine

This document covers both install paths for running a local LLM on-device.

## PATH 1 (recommended v0.1): llama-server in Termux

The user runs `llama-server` inside [Termux](https://termux.com/) (F-Droid build,
not the Play Store version). The keyboard app connects via HTTP to
`127.0.0.1:8080`.

This path is sufficient when:
- You already have Termux
- You want to customise the build flags (CUDA, Vulkan, BLAS)
- You want to control server lifecycle manually

### Step 1 — Install Termux

Get **Termux from F-Droid** — the Play Store version is abandoned and will not
work with the commands below.

```bash
pkg update && pkg upgrade -y
```

### Step 2 — Build llama-server

```bash
pkg install git cmake clang ninja

git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp

# Minimal CPU build (works on all devices)
cmake -B build -G Ninja
cmake --build build -j 4

# Optional: GPU offload via Vulkan (if device supports Vulkan)
# pkg install vulkan-loader spirv-headers
# cmake -B build -G Ninja -DGGML_VULKAN=ON
# cmake --build build -j 4

# Link into $HOME so it's always in PATH
ln -sf "$PWD/build/bin/llama-server" "$HOME/llama-server"
```

### Step 3 — Download a model

You need a `.gguf` quantised model. For a small device-friendly model:

```bash
mkdir -p ~/models

# Gemma 3 2B — extremely fast, good for rewrites
# (download from HuggingFace or a mirror)
wget -O ~/models/gemma-3n-E2B-it-UD-IQ2_M.gguf \
  https://huggingface.co/SomeUser/gemma-3n-2B-IQ2_M-GGUF/resolve/main/gemma-3n-2B-IQ2_M.gguf

# Or use any GGUF model — set MODEL env var to point at it.
```

### Step 4 — Create CTE directory structure

```bash
mkdir -p ~/cte/logs ~/cte/runs
```

The app will seed configs to `/sdcard/Android/data/dev.patrickgold.florisboard.vault/files/cte/`
on first launch. The `scripts/` directory inside that tree is **not writable by
Termux** (it lives in the app's private storage). To use the script from Termux:

```bash
# Symlink for convenience:
ln -sf /sdcard/Android/data/dev.patrickgold.florisboard.vault/files/cte/scripts ~/cte/scripts
```

### Step 5 — Start the server

```bash
# Using the bundled init script:
bash ~/cte/scripts/start_llama.sh

# Or directly:
llama-server \
  --model ~/models/gemma-3n-E2B-it-UD-IQ2_M.gguf \
  --host 127.0.0.1 \
  --port 8080 \
  --n-gpu-layers 0 \
  --ctx-size 4096 \
  --cont-batching \
  --metrics
```

Flags explained:
| Flag | Purpose |
|------|---------|
| `--host 127.0.0.1` | Bind to localhost only (security: no network exposure) |
| `--port 8080` | Default port expected by the keyboard app |
| `--n-gpu-layers N` | GPU offload: 0 = CPU only, 99 = full GPU |
| `--ctx-size N` | Context window (memory → ~512 MB per 4096 tokens) |
| `--cont-batching` | Continuous batching for multi-turn conversations |
| `--metrics` | Expose `/metrics` endpoint (tokens/sec telemetry) |

### Step 6 — Verify

```bash
# From another Termux session or adb shell:
curl -s http://127.0.0.1:8080/health
# Expected: {"status":"ok","uptime_s":...}

curl -s http://127.0.0.1:8080/metrics | grep llama_request_tokens_per_second
# Expected: llama_request_tokens_per_second 12.3
```

### Step 7 — Test from the keyboard

1. Open **Settings → CTE Configuration** (deep link: `ui://florisboard/cte`)
2. Tap **Test Connection** — should show "LLaMA OK — 42ms"
3. Tap **Start Monitor (foreground)** — a sticky notification appears with
   live health and tok/s metrics
4. In any text field, type `/fix teh sentence` — the local model rewrites it

---

## PATH 2 (v0.2+): Bundled .so via JNI

**Not implemented in v0.1.** The following is the design.

A native `libllama.so` is built from llama.cpp with `-DGGML_CPU=ON` and
bundled in `app/src/main/jniLibs/arm64-v8a/`. A future version will:

1. Ship the .so alongside a tiny GGUF model inside the APK
2. Load via `System.loadLibrary("llama")` in [NativeBackend](src/main/java/dev/patrickgold/florisboard/ime/ai/providers/LlamaCppLocal.kt)
3. Expose an OpenAI-compatible localhost server OR call the native inference
   directly from Kotlin via JNI (bypassing HTTP entirely)

Stubs are in place:
- `NativeBackend.loadLibrary()` → `System.loadLibrary("llama")`
- `NativeBackend.loadModel(path)` → set model path
- `NativeBackend.infer(system, user, maxTokens)` → return text
- `NativeBackend.unload()` → free model

### Why not PATH 2 in v0.1?

- Bundling the .so adds ~20 MB to the APK per ABI
- The .gguf model is typically 1–4 GB and cannot be bundled
- Termux workflows are more flexible for power users
- JNI crashes in the IME process cause the keyboard to disappear, which is a
  poor UX. An external process (llama-server) crashes independently.

---

## Foreground Service Monitoring

The `LlamaServerService` is an Android foreground `Service` that:

| Aspect | Detail |
|--------|--------|
| Poll frequency | Every 5 seconds |
| Endpoints polled | `GET /health`, `GET /metrics` |
| Notification | Sticky, shows "LLaMA: Online 42ms · 12.3 tok/s" or "LLaMA: Offline" |
| Tap notification | Opens CTE settings |
| Auto-shutdown | 10 min idle + screen off |
| Ping API | `LlamaServerService.ping(context)` resets idle timer |

### Starting the service

```kotlin
// From any context (IME, Activity):
LlamaServerService.start(context)
```

### Resetting the idle timer from the IME

In the IME service (or any completion request path):

```kotlin
LlamaServerService.ping(context)
```

### Shutdown rules

1. Screen ON → service stays alive indefinitely during active use
2. Screen OFF → idle timer starts
3. Idle timer reaches 10 min → service stops itself
4. Next AI request via `ping()` → service restarts
5. Manual stop: `LlamaServerService.stop(context)`

---

## Fire-and-forget vs interactive

The local server uses blocking HTTP calls. For interactive typing (e.g. inline
rewrites), latency should be <500 ms. On-device models typically achieve 10–30
tok/s on a modern phone SoC (Snapdragon 8 Gen 2 / Dimensity 9200).

If the model is too slow:
- Reduce `--ctx-size` to 2048
- Increase `--n-gpu-layers` (requires Vulkan build)
- Use a smaller quant (Q2_K or IQ2_M)
- Try a smaller model (e.g. Gemma 3 2B instead of Llama 3 8B)

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| "Connection refused" | Is llama-server running? `ps aux \| grep llama` |
| "No key stored" in logs | The provider has a `keyRef` but you're using local; remove keyRef from triggers.json |
| Notification shows "Unreachable" | Server not running or wrong host/port |
| "LLaMA FAIL — timeout" | Model too large for device; try a smaller one |
| Keyboard crashes | Check `adb logcat \| grep AndroidRuntime` — likely OOM |
| Slow inference | Lower `--ctx-size`, enable GPU offload |
| Test passes but triggers fail | Check triggers.json `local` provider URL matches |
