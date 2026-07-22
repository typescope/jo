#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

cleanup() {
    rm -f "$DIR/actual.out" "$DIR/app.py" "$DIR/resources/fixture/links/escape.txt"
}

cleanup
mkdir -p "$DIR/resources/fixture/links"
ln -s "$DIR/outside.txt" "$DIR/resources/fixture/links/escape.txt"

echo "  - Compiling with Python backend"
"$PROJECT_ROOT/bin/jo" compile --python --use-runtime-api python "$DIR/app.jo" -o "$DIR/app.py"

(cd /tmp && python3 "$DIR/app.py") > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Python resource bundle test failed"
    cat "$DIR/actual.out"
    exit 1
}

cleanup

echo "  ✓ All tests passed for $TEST_NAME"
