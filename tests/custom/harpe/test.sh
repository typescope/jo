#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.py"

echo "  - Compiling with Python backend"
"$PROJECT_ROOT/bin/jo" compile --python --test-pickling --use-runtime-api python "$DIR/Model.jo" "$DIR/Anthropic.jo" -o "$DIR/app.py"
python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Python dynamic dispatch test failed"
    cat "$DIR/actual.out"
    exit 1
}

echo "  ✓ All tests passed for $TEST_NAME"
