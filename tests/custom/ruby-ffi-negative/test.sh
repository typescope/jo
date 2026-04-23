#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

run_case() {
    local name="$1"
    local src="$DIR/$name.jo"
    local out="$DIR/actual.out"
    local rb="$DIR/$name.rb"
    local expect="$DIR/$name.jo.check"

    echo "  - $name"
    rm -f "$out" "$rb"

    if "$PROJECT_ROOT/bin/jo" compile --ruby --use-runtime-api ruby "$src" -o "$rb" > "$out" 2>&1; then
        echo "[error] Expected compilation failure for $name"
        cat "$out"
        exit 1
    fi

    diff "$out" "$expect" || {
        echo "[error] Negative Ruby FFI test failed for $name"
        cat "$out"
        exit 1
    }
}

for src in "$DIR"/*.jo; do
    run_case "$(basename "${src%.jo}")"
done

rm -f "$DIR/actual.out" "$DIR"/*.rb

echo "  ✓ All tests passed for $TEST_NAME"
