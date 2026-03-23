#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$SCRIPT_DIR")"

# Get the project root (three levels up from tests/custom)
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

# Clean previous build
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out/api" "$SCRIPT_DIR/out/runtime"

echo "📦 Step 1: Compile API"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/Api.jo" -d "$SCRIPT_DIR/out/api"
echo "✅ API compiled"
echo ""

echo "📦 Step 2: Compile Runtime"
"$PROJECT_ROOT/bin/jo" compile --sast "$SCRIPT_DIR/Runtime.jo" \
  --lib "$PROJECT_ROOT/libs/runtime-js":"$SCRIPT_DIR/out/api" \
  -d "$SCRIPT_DIR/out/runtime"
echo "✅ Runtime compiled"
echo ""

echo "📦 Step 3: Compile User Application"
"$PROJECT_ROOT/bin/jo" compile --js \
  --link jo.main=Runtime.main \
  --link Api.appMain=App.main \
  --lib "$SCRIPT_DIR/out/api" \
  --runtime "$SCRIPT_DIR/out/runtime" \
  "$SCRIPT_DIR/App.jo" \
  -o "$SCRIPT_DIR/out/app.js"
echo "✅ User app compiled"
echo ""

echo "✅ Build complete!"
echo ""

node "$SCRIPT_DIR/out/app.js" "$SCRIPT_DIR" > "$SCRIPT_DIR/actual.out" 2>&1
diff "$SCRIPT_DIR/actual.out" "$SCRIPT_DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$SCRIPT_DIR/out"
