#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  - Building with register machine"
"$PROJECT_ROOT/bin/jo" build -no-runtime -reg "$DIR/app.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-native" \
  -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

echo "  - Building with stack machine"
"$PROJECT_ROOT/bin/jo" build -no-runtime -stack "$DIR/app.jo" \
  -lib "$PROJECT_ROOT/libs/runtime-native" \
  -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
