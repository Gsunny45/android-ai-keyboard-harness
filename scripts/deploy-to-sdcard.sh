#!/bin/bash
# deploy-to-sdcard.sh — Deploy GGUF models and configs to external SD card storage
# TODO: Implement adb push / Termux storage symlink workflow

set -e

echo "=== agentA-Z: SD Card Deploy ==="
echo "Status: placeholder — not yet implemented"
echo ""
echo "TODO:"
echo "  1. Detect SD card mount path (/sdcard or /storage/XXXX-XXXX)"
echo "  2. Create target directory: /sdcard/agentAZ/models/"
echo "  3. Copy GGUF model files from ~/models/"
echo "  4. Copy triggers.json and per-app profiles"
echo "  5. Update llama-server startup script to reference SD card path"

exit 1
