#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

cleanup() {
    rm -f "$DIR/actual.out" "$DIR/app.js" "$DIR/resources/fixture/links/escape.txt"
}

cleanup
mkdir -p "$DIR/resources/fixture/links"
ln -s "$DIR/outside.txt" "$DIR/resources/fixture/links/escape.txt"

echo "  - Compiling with JavaScript backend"
"$PROJECT_ROOT/bin/jo" compile --js --use-runtime-api js "$DIR/app.jo" -o "$DIR/app.js"

(cd /tmp && node "$DIR/app.js") > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript resource bundle test failed"
    cat "$DIR/actual.out"
    exit 1
}

cleanup

echo "  ✓ All tests passed for $TEST_NAME"
