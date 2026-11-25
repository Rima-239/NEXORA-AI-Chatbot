#!/usr/bin/env bash
# NEXORA Linux / macOS Build Script
set -e

echo "[NEXORA] Compiling Core Java backend..."
mkdir -p bin

javac -d bin -encoding UTF-8 -sourcepath src \
    src/com/nexora/Main.java \
    src/com/nexora/model/*.java \
    src/com/nexora/server/*.java \
    src/com/nexora/controller/*.java \
    src/com/nexora/service/*.java \
    src/com/nexora/util/*.java

echo "[NEXORA] Build Successful! Output directory: bin/"
