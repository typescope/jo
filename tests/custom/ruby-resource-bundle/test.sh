#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

cleanup() {
    rm -f "$DIR/actual.out" "$DIR/app.rb" "$DIR/resources/fixture/links/escape.txt"
}

cleanup
mkdir -p "$DIR/resources/fixture/links"
ln -s "$DIR/outside.txt" "$DIR/resources/fixture/links/escape.txt"

echo "  - Compiling with Ruby backend"
"$PROJECT_ROOT/bin/jo" compile --ruby --use-runtime-api ruby "$DIR/app.jo" -o "$DIR/app.rb"

(cd "$DIR" && ruby app.rb) > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby resource bundle test failed"
    cat "$DIR/actual.out"
    exit 1
}

cleanup

echo "  ✓ All tests passed for $TEST_NAME"
