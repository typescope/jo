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
echo "  - Uses py.python intrinsic"
echo "  - Provides context via 'with' clause"
echo "  - Links to PlatformAPI interface"
echo "  - Links to Python runtime for I/O"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/PlatformRuntime.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-python":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✓ PlatformRuntime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (Periodic health checker)"
echo "------------------------------------------------------"
echo "  - Receives context parameters: process, system, logger, mailer"
echo "  - Custom entry point: SystemRuntime.platformMain"
echo "  - Cannot access Python directly"
"$PROJECT_ROOT/bin/jo" build -python \
  -link jo.main=SystemRuntime.platformMain \
  -link SystemAPI.startMonitor=ProcessMonitor.startMonitor \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/monitor.py"
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
