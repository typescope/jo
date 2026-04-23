#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.py"

if ! "$PROJECT_ROOT/bin/jo" compile --python --use-runtime-api python \
    "$DIR/app.jo" -o "$DIR/app.py"; then
    echo "[error] Compilation failed"
    exit 1
fi

python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Test failed"
    cat "$DIR/actual.out"
    exit 1
}

rm -f "$DIR/actual.out" "$DIR/app.py"
echo "  ✓ All tests passed for $TEST_NAME"
