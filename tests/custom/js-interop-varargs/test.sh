#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.js"

if ! "$PROJECT_ROOT/bin/jo" compile --js --use-runtime-api js \
    "$DIR/app.jo" -o "$DIR/app.js"; then
    echo "[error] Compilation failed"
    exit 1
fi

node "$DIR/app.js" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Test failed"
    cat "$DIR/actual.out"
    exit 1
}

rm -f "$DIR/actual.out" "$DIR/app.js"
echo "  ✓ All tests passed for $TEST_NAME"
