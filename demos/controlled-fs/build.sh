#!/bin/bash
# Build script for controlled file system demo
# Demonstrates sandboxed file access via context parameters

set -e  # Exit on error

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Get the project root (two levels up from demos/controlled-fs)
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=========================================="
echo "Controlled File System - Sandbox Demo"
echo "=========================================="
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo ""
echo "Stage 1: Compile FileSystemAPI.jo (Pure API with context params)"
echo "----------------------------------------------------------------"
echo "  Declares: FileSystem, Logger interfaces"
echo "  Data types: FileEntry, ReadResult (union)"
echo "  Context params: fs, logger"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/FileSystemAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "  API compiled to: out/api/"
echo ""

echo "Stage 2: Compile FileSystemRuntime.jo (Sandbox enforcement)"
echo "------------------------------------------------------------"
echo "  - Uses js.javascript intrinsic for Node.js fs module"
echo "  - Validates all paths stay within sandbox root"
echo "  - Provides context via 'with' clause"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/FileSystemRuntime.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-js":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "  Runtime compiled to: out/runtime/"
echo ""

echo "Stage 3: Compile UserApp.jo (File explorer)"
echo "---------------------------------------------"
echo "  - Receives context parameters: fs, logger"
echo "  - Custom entry point: FileSystemRuntime.platformMain"
echo "  - Cannot access Node.js directly"
"$PROJECT_ROOT/bin/jo" build -js \
  -link jo.main=FileSystemRuntime.platformMain \
  -link FileSystemAPI.exploreFiles=FileExplorer.exploreFiles \
  -lib "$SCRIPT_DIR/out/api" \
  -runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/UserApp.jo" \
  -o "$SCRIPT_DIR/out/app.js"
echo "  UserApp compiled to: out/app.js"
echo ""

# Initialize sandbox
echo "=========================================="
echo "Initializing sandbox directories..."
echo "=========================================="
SANDBOX_PATH="$SCRIPT_DIR/sandbox"
rm -rf "$SANDBOX_PATH"
mkdir "$SANDBOX_PATH"
echo ""

# Run the demo
echo "=========================================="
echo "Running File Explorer..."
echo "=========================================="
echo ""
node "$SCRIPT_DIR/out/app.js" "$SANDBOX_PATH"
echo ""
echo "=========================================="
echo "Done!"
echo "=========================================="
