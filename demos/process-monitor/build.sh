#!/bin/bash
# Build script for system monitoring example using context parameters
# Demonstrates extending Jo runtime with context parameter pattern

set -e  # Exit on error

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=========================================="
echo "System Monitor - Context Parameters Demo"
echo "=========================================="
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo ""
echo "Stage 1: Compile PlatformAPI.jo (Pure API with context params)"
echo "----------------------------------------------------------------"
echo "  Declares: Process, System, Logger types"
echo "  Context params: process, system, logger"
bin/jo build-lib "$SCRIPT_DIR/PlatformAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "✓ PlatformAPI compiled to: out/api/"
echo ""

echo "Stage 2: Compile PlatformRuntime.jo (Context param providers)"
echo "---------------------------------------------------------------"
echo "  - Uses jo.runtime.JS.js intrinsic"
echo "  - Provides context via 'with' clause"
echo "  - Links to PlatformAPI interface"
echo "  - Links to JS runtime for I/O"
bin/jo build-lib "$SCRIPT_DIR/PlatformRuntime.jo" \
  -lib libs/runtime-js:"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (Process analyzer)"
echo "------------------------------------------------"
echo "  - Receives context parameters: process, logger"
echo "  - Custom entry point: SystemRuntime.platformMain"
echo "  - Cannot access Node.js directly"
bin/jo build -js \
  -no-detect-main \
  -link jo.Main.main=SystemRuntime.platformMain \
  -link SystemAPI.Monitor.analyzeSystem=ProcessAnalyzer.Analysis.analyzeSystem \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/monitor.js"
echo "✓ UserApp compiled to: out/monitor.js"
echo ""

echo "=========================================="
echo "Running System Monitor..."
echo "=========================================="
echo ""
node "$SCRIPT_DIR/out/monitor.js"
echo ""
echo "=========================================="
echo "Done!"
echo "=========================================="
