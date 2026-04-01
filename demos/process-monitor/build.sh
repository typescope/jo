#!/bin/bash
# Build script for process monitoring demo
# Demonstrates extending Jo runtime with context parameter pattern

set -e  # Exit on error

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Get the project root (two levels up from demos/process-monitor)
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=========================================="
echo "Process Monitor - Context Parameters Demo"
echo "=========================================="

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo ""
echo "Stage 1: Compile PlatformAPI.jo (Pure API with context params)"
echo "----------------------------------------------------------------"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/out/api" "$SCRIPT_DIR/PlatformAPI.jo"
echo "✓ PlatformAPI compiled to: out/api/"
echo ""

echo "Stage 2: Compile PlatformRuntime.jo (Context param providers)"
echo "---------------------------------------------------------------"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/out/runtime" --use-runtime-api python "$SCRIPT_DIR/PlatformRuntime.jo" \
  --lib "$SCRIPT_DIR/out/api"
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (Periodic health checker)"
echo "------------------------------------------------------"
"$PROJECT_ROOT/bin/jo" compile --python \
  --link jo.main=SystemRuntime.platformMain \
  --link SystemAPI.startMonitor=ProcessMonitor.startMonitor \
  --lib "$SCRIPT_DIR/out/api" \
  --link-lib "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" -o "$SCRIPT_DIR/out/monitor.py"
echo "✓ UserApp compiled to: out/monitor.py"
echo ""

echo "=========================================="
echo "Running Process Monitor (one-shot)..."
echo "=========================================="
echo ""
MONITOR_INTERVAL_SECS=0 python3 "$SCRIPT_DIR/out/monitor.py"
echo ""
echo "=========================================="
echo "Done!"
echo "=========================================="
