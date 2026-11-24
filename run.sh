#!/usr/bin/env bash
# NEXORA Linux / macOS Launch Script
set -e

if [ ! -f "bin/com/nexora/Main.class" ]; then
    echo "[NEXORA] Binaries not found. Building project first..."
    bash build.sh
fi

echo "[NEXORA] Starting NEXORA server..."
exec java -cp bin com.nexora.Main
