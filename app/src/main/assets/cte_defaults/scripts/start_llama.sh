#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# start_llama.sh — Launch llama-server in Termux
# ============================================================================
# PATH 1: The user runs llama-server inside Termux.
#
# Prerequisites (run once):
#   pkg install git cmake clang
#   git clone https://github.com/ggml-org/llama.cpp
#   cd llama.cpp && cmake -B build && cmake --build build -j 4
#   ln -s $PWD/build/bin/llama-server ~/llama-server
#
# Usage:
#   ~/cte/scripts/start_llama.sh
#
# Environment variables (optional):
#   MODEL       Path to .gguf file (default: ~/models/gemma-3n-E2B-it-UD-IQ2_M.gguf)
#   HOST        Bind address  (default: 127.0.0.1)
#   PORT        Listen port   (default: 8080)
#   N_GPU       GPU layers    (default: 0 = CPU only; set to 99 for full offload)
#   CTX_SIZE    Context size  (default: 4096)
#   N_PARALLEL  Parallel decodes (default: 1)
# ============================================================================

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────
MODEL="${MODEL:-$HOME/models/gemma-3n-E2B-it-UD-IQ2_M.gguf}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
N_GPU="${N_GPU:-0}"
CTX_SIZE="${CTX_SIZE:-4096}"
N_PARALLEL="${N_PARALLEL:-1}"

LLAMA_SERVER="${HOME}/llama-server"
LOG_DIR="${HOME}/cte/logs"
LOG_FILE="${LOG_DIR}/llama-server-$(date +%Y%m%d-%H%M%S).log"
PID_FILE="${LOG_DIR}/llama-server.pid"

# ── Helpers ───────────────────────────────────────────────────────────────
die() { echo "[FATAL] $*" >&2; exit 1; }
info() { echo "[INFO] $*"; }

# ── Preflight checks ─────────────────────────────────────────────────────
if [ ! -f "$LLAMA_SERVER" ]; then
    die "llama-server not found at $LLAMA_SERVER. Build it first:"
    die "  cd llama.cpp && cmake -B build && cmake --build build -j 4"
    die "  ln -s \$PWD/build/bin/llama-server ~/llama-server"
fi

if [ ! -f "$MODEL" ]; then
    die "Model not found: $MODEL"
    die "Download a .gguf file and set the MODEL env var."
fi

mkdir -p "$LOG_DIR"

# Port conflict check
if lsof -i ":$PORT" &>/dev/null 2>&1; then
    info "Port $PORT is already in use. Checking if it's our process..."
    OLD_PID=""
    [ -f "$PID_FILE" ] && OLD_PID=$(cat "$PID_FILE")
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        die "llama-server already running (PID $OLD_PID). Use 'kill $OLD_PID' first."
    fi
    die "Port $PORT is in use by another process."
fi

# ── Launch ────────────────────────────────────────────────────────────────
info "============================================"
info " Starting llama-server"
info " Model:   $MODEL"
info " Host:    $HOST:$PORT"
info " GPU:     ${N_GPU} layers"
info " Context: $CTX_SIZE tokens"
info " Log:     $LOG_FILE"
info "============================================"

nohup "$LLAMA_SERVER" \
    --model "$MODEL" \
    --host "$HOST" \
    --port "$PORT" \
    --n-gpu-layers "$N_GPU" \
    --ctx-size "$CTX_SIZE" \
    --parallel "$N_PARALLEL" \
    --cont-batching \
    --metrics \
    > "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"
info "llama-server started (PID $PID)"
info "Log: $LOG_FILE"
info ""
info "Use 'kill $PID' to stop."
info "Monitor: tail -f $LOG_FILE"
