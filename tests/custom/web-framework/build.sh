#!/bin/bash
# Build script for system monitoring example
# Demonstrates extending Jo runtime with real Node.js capabilities

set -e  # Exit on error

echo "======================================"
echo "System Monitor - Custom Runtime Demo"
echo "======================================"
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf out/
mkdir -p out

echo ""
echo "Stage 1: Compile PlatformAPI.stk (Pure API declarations)"
echo "--------------------------------------------------------"
echo "  Declares: Process, System, Logging capabilities"
bin/jo build-lib tests/custom/web-framework/PlatformAPI.stk -d tests/custom/web-framework/out/api
echo "✓ PlatformAPI compiled to: out/api/"
echo ""

echo "Stage 2: Compile PlatformRuntime.stk (Runtime using Node.js APIs)"
echo "------------------------------------------------------------------"
echo "  - Uses stk.runtime.JS.js intrinsic"
echo "  - Calls Node.js: child_process, os, process"
echo "  - Links to PlatformAPI interface"
echo "  - Links to JS runtime for I/O"
bin/jo build-lib tests/custom/web-framework/PlatformRuntime.stk \
  -lib sast/runtime/js:tests/custom/web-framework/out/api \
  -d tests/custom/web-framework/out/runtime
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.stk (Process analyzer)"
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
  -lib tests/custom/web-framework/out/api \
  -runtime tests/custom/web-framework/out/runtime \
  tests/custom/web-framework/UserApp.stk \
  -o tests/custom/web-framework/out/monitor.js
echo "✓ UserApp compiled to: out/monitor.js"
echo ""

echo "======================================"
echo "Running System Monitor..."
echo "======================================"
echo ""
node tests/custom/web-framework/out/monitor.js
echo ""
echo "======================================"
echo "Done!"
echo "======================================"
