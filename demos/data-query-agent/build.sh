#!/bin/bash
# Build script for data-query-agent demo
# Pre-compiles DatabaseAPI and Runtime libraries (Python backend)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "Building data-query-agent libraries..."
echo ""

# Clean previous builds
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"

echo "Stage 1: Compile DatabaseAPI.jo"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/out/api" "$SCRIPT_DIR/DatabaseAPI.jo"
echo "  -> out/api/"
echo ""

echo "Stage 2: Compile Runtime.jo (Python backend)"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/out/runtime" "$SCRIPT_DIR/Runtime.jo" \
  --lib "$PROJECT_ROOT/libs/runtime-python" --lib "$SCRIPT_DIR/out/api"
echo "  -> out/runtime/"
echo ""

# Initialize database if it doesn't exist
DB_PATH="$SCRIPT_DIR/database.db"
if [ ! -f "$DB_PATH" ]; then
    echo "Initializing database..."
    python3 "$SCRIPT_DIR/init_db.py" "$DB_PATH"
    echo ""
fi

echo "Stage 3: Compile Sample LLM generated Jo code"
"$PROJECT_ROOT/bin/jo" compile --python \
  --link jo.main=DatabaseRuntime.platformMain \
  --link DatabaseAPI.analyzeDocuments=UserTask.analyzeDocuments \
  --lib "$SCRIPT_DIR/out/api" \
  --link-lib "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/llm_sample.jo" -o "$SCRIPT_DIR/out/llm_sample.py"
echo "Build complete."

echo "Stage 4: Run query"
(cd "$SCRIPT_DIR" && python3 "out/llm_sample.py" 1 "$DB_PATH" "./skills")

echo "Build Complete"
