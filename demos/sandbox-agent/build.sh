#!/bin/bash
# Build script for sandbox agent demo
# Pre-compiles the AgentAPI and AgentRuntime libraries

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "Building sandbox agent libraries..."
echo ""

# Clean previous builds
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo "Stage 1: Compile AgentAPI.jo"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/AgentAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "  -> out/api/"
echo ""

echo "Stage 2: Compile AgentRuntime.jo"
"$PROJECT_ROOT/bin/jo" build-lib "$SCRIPT_DIR/AgentRuntime.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-python":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "  -> out/runtime/"
echo ""

echo "Build complete."
