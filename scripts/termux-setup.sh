#!/bin/bash
# termux-setup.sh — One-click llama.cpp server setup for Qwen2.5-Coder-1.5B on Android
set -e

echo "=== agentA-Z: Termux LLM Server Setup ==="

# Install dependencies
pkg update -y
pkg install -y cmake git make clang

# Clone and build llama.cpp
if [ ! -d "llama.cpp" ]; then
    git clone https://github.com/ggml-org/llama.cpp
fi
cd llama.cpp
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . -j$(nproc)
cd ../..

# Create model directory
mkdir -p ~/models

echo ""
echo "=== Build complete ==="
echo "Next steps:"
echo "1. Download your model: huggingface-cli download Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF qwen2.5-coder-1.5b-instruct-q6_k.gguf --local-dir ~/models"
echo "2. Start server: ./llama.cpp/build/bin/llama-server -m ~/models/qwen2.5-coder-1.5b-instruct-q6_k.gguf -c 4096 -t 4 --host 127.0.0.1 --port 8080"
echo "3. Test: curl http://127.0.0.1:8080/v1/chat/completions -d '{\"model\":\"qwen\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}'"
