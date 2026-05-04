#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -f "$DIR/actual.out" "$DIR/app.rb" "$DIR/combined.rb"

if ! "$PROJECT_ROOT/bin/jo" compile --ruby --use-runtime-api ruby \
    "$DIR/app.jo" -o "$DIR/app.rb"; then
    echo "[error] Compilation failed"
    exit 1
fi

# Prepend helper Ruby classes before the compiled output
cat "$DIR/helper.rb" "$DIR/app.rb" > "$DIR/combined.rb"

ruby "$DIR/combined.rb" > "$DIR/actual.out" 2>&1

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Test failed"
    cat "$DIR/actual.out"
    exit 1
}

rm -f "$DIR/actual.out" "$DIR/app.rb" "$DIR/combined.rb"
echo "  ✓ All tests passed for $TEST_NAME"
