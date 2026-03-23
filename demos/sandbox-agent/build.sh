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
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/AgentAPI.jo" -d "$SCRIPT_DIR/out/api"
echo "  -> out/api/"
echo ""

echo "Stage 2: Compile AgentRuntime.jo"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/AgentRuntime.jo" \
  --lib "$PROJECT_ROOT/libs/runtime-python" --lib "$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "  -> out/runtime/"
echo ""

echo "Stage 3: Compile smoke test (llm_sample.jo)"
"$PROJECT_ROOT/bin/jo" compile --python \
  --link jo.main=AgentRuntime.platformMain \
  --link AgentAPI.runTask=UserTask.runTask \
  --lib "$SCRIPT_DIR/out/api" \
  --link-lib "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/llm_sample.jo" -o "$SCRIPT_DIR/out/llm_sample.py"
echo "  -> out/llm_sample.py"
echo ""

echo "Stage 4: Run smoke test"
(cd "$SCRIPT_DIR" && python3 "out/llm_sample.py" "$SCRIPT_DIR/sandbox" "$SCRIPT_DIR/skills")

echo "Build complete."
