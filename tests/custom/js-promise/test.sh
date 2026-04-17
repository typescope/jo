#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.js"

echo "  - Compiling with JavaScript backend"
"$PROJECT_ROOT/bin/jsc" --use-runtime-api js "$DIR/app.jo" -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript Promise test failed"
    cat "$DIR/actual.out"
    exit 1
}

echo "  ✓ All tests passed for $TEST_NAME"
