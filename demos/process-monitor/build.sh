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
echo ""
echo "Required environment variables for email alerts:"
echo "  GMAIL_ACCESS_TOKEN      - OAuth2 access token for Gmail API"
echo "  ALERT_EMAIL_RECIPIENT   - Recipient email address"
echo ""
echo "If unset, the monitor still runs but alert emails are skipped."
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo ""
echo "Stage 1: Compile PlatformAPI.jo (Pure API with context params)"
echo "----------------------------------------------------------------"
echo "  Declares: Process, System, Logger, Timer, Mailer types"
echo "  Context params: process, system, logger, timer, mailer"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/PlatformAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "✓ PlatformAPI compiled to: out/api/"
echo ""

echo "Stage 2: Compile PlatformRuntime.jo (Context param providers)"
echo "---------------------------------------------------------------"
echo "  - Uses js.javascript intrinsic"
echo "  - Provides context via 'with' clause"
echo "  - Links to PlatformAPI interface"
echo "  - Links to JS runtime for I/O"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/PlatformRuntime.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-js":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (Periodic health checker)"
echo "------------------------------------------------------"
echo "  - Receives context parameters: process, system, logger, mailer"
echo "  - Custom entry point: SystemRuntime.platformMain"
echo "  - Cannot access Node.js directly"
"$PROJECT_ROOT/bin/jo" build -js \
  -link jo.main=SystemRuntime.platformMain \
  -link SystemAPI.Monitor.checkAndAlert=ProcessMonitor.Analysis.checkAndAlert \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/monitor.js"
echo "✓ UserApp compiled to: out/monitor.js"
echo ""

echo "=========================================="
echo "Running Process Monitor (Ctrl+C to stop)..."
echo "=========================================="
echo ""
node "$SCRIPT_DIR/out/monitor.js"
echo ""
echo "=========================================="
echo "Done!"
echo "=========================================="
