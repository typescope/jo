#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.rb"

echo "  - Compiling with Ruby backend"
"$PROJECT_ROOT/bin/jo" compile --ruby --use-runtime-api ruby "$DIR/app.jo" -o "$DIR/app.rb"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby annotation wrapper test failed"
    cat "$DIR/actual.out"
    exit 1
}

echo "  ✓ All tests passed for $TEST_NAME"
