#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

run_test() {
    local backend="$1"
    local binary="$DIR/app.run"

    echo "  - Building with $backend"
    "$PROJECT_ROOT/bin/jo" compile --use-runtime-api native "$backend" "$DIR/app.jo" \
        -o "$binary"

    "$binary" > "$DIR/actual.out" 2>&1
    local exit_code=$?

    if [ "$exit_code" -ne 1 ]; then
        echo "[error] $backend: expected exit code 1, got $exit_code"
        exit 1
    fi

    diff "$DIR/actual.out" "$DIR/expect.check" || {
        echo "[error] $backend: output mismatch for $TEST_NAME"
        cat "$DIR/actual.out"
        exit 1
    }
}

run_test --reg
run_test --stack

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
