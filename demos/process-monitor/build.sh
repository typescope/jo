#!/bin/bash
# Build script for system monitoring example
# Demonstrates extending Jo runtime with real Node.js capabilities

set -e  # Exit on error

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "======================================"
echo "System Monitor - Custom Runtime Demo"
echo "======================================"
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo ""
echo "Stage 1: Compile PlatformAPI.jo (Pure API declarations)"
echo "--------------------------------------------------------"
echo "  Declares: Process, System, Logging capabilities"
bin/jo build-lib "$SCRIPT_DIR/PlatformAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "✓ PlatformAPI compiled to: out/api/"
echo ""

echo "Stage 2: Compile PlatformRuntime.jo (Runtime using Node.js APIs)"
echo "------------------------------------------------------------------"
echo "  - Uses stk.runtime.JS.js intrinsic"
echo "  - Calls Node.js: child_process, os, process"
echo "  - Links to PlatformAPI interface"
echo "  - Links to JS runtime for I/O"
bin/jo build-lib "$SCRIPT_DIR/PlatformRuntime.jo" \
  -lib libs/runtime-js:"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (Process analyzer)"
echo "------------------------------------------------"
echo "  - Uses ONLY SystemAPI capabilities"
echo "  - Custom entry point: SystemAPI.Monitor.startMonitor"
echo "  - Cannot access Node.js directly"
bin/jo build -js \
  -no-detect-main \
  -link stk.Main.main=SystemAPI.Monitor.startMonitor \
  -link SystemAPI.Process.listProcesses=SystemRuntime.ProcessImpl.listProcesses \
  -link SystemAPI.Process.countProcesses=SystemRuntime.ProcessImpl.countProcesses \
  -link SystemAPI.Process.findByName=SystemRuntime.ProcessImpl.findByName \
  -link SystemAPI.Process.getCurrentPID=SystemRuntime.ProcessImpl.getCurrentPID \
  -link SystemAPI.Process.getCurrentMemoryMB=SystemRuntime.ProcessImpl.getCurrentMemoryMB \
  -link SystemAPI.System.uptime=SystemRuntime.SystemImpl.uptime \
  -link SystemAPI.System.platform=SystemRuntime.SystemImpl.platform \
  -link SystemAPI.System.arch=SystemRuntime.SystemImpl.arch \
  -link SystemAPI.System.hostname=SystemRuntime.SystemImpl.hostname \
  -link SystemAPI.Logging.info=SystemRuntime.LoggingImpl.info \
  -link SystemAPI.Logging.debug=SystemRuntime.LoggingImpl.debug \
  -link SystemAPI.Monitor.analyzeSystem=ProcessAnalyzer.Analysis.analyzeSystem \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/monitor.js"
echo "✓ UserApp compiled to: out/monitor.js"
echo ""

echo "======================================"
echo "Running System Monitor..."
echo "======================================"
echo ""
node "$SCRIPT_DIR/out/monitor.js"
echo ""
echo "======================================"
echo "Done!"
echo "======================================"
