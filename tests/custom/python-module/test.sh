#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

run_case() {
    local name="$1"
    local src="$DIR/$name.jo"
    local py="$DIR/$name.py"
    local out="$DIR/actual.out"
    local expect="$DIR/$name.jo.check"

    echo "  - $name"
    rm -f "$py" "$out"
    rm -rf "$PROJECT_ROOT/test-tmp-os" "$PROJECT_ROOT/test-py-os-pathlib-tmp"

    if ! "$PROJECT_ROOT/bin/jo" compile --python --use-runtime-api python "$src" -o "$py"; then
        echo "[error] Python module compilation failed for $name"
        exit 1
    fi

    python3 "$py" > "$out" 2>&1

    diff "$out" "$expect" || {
        echo "[error] Python module test failed for $name"
        cat "$out"
        exit 1
    }
}

for src in "$DIR"/*.jo; do
    run_case "$(basename "${src%.jo}")"
done

rm -rf "$PROJECT_ROOT/test-tmp-os" "$PROJECT_ROOT/test-py-os-pathlib-tmp"
rm -f "$DIR/actual.out" "$DIR"/*.py

echo "  ✓ All tests passed for $TEST_NAME"
