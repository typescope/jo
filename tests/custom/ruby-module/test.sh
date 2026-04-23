#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

LIB_DIR="$DIR/lib"
LIB_OUT="$DIR/out/lib"

# Stage 1: compile wrapper libs once
rm -rf "$DIR/out"
mkdir -p "$LIB_OUT"
if ! "$PROJECT_ROOT/bin/jo" compile --sast "$LIB_OUT" --use-runtime-api ruby \
    "$LIB_DIR/file.jo" "$LIB_DIR/pathname.jo"; then
    echo "[error] Failed to compile ruby-module wrappers"
    exit 1
fi

run_case() {
    local name="$1"
    local src="$DIR/$name.jo"
    local rb="$DIR/$name.rb"
    local out="$DIR/actual.out"
    local expect="$DIR/$name.jo.check"

    echo "  - $name"
    rm -f "$rb" "$out"
    rm -rf "$PROJECT_ROOT/test-tmp-ruby-file" "$PROJECT_ROOT/test-tmp-ruby-pathname"

    if ! "$PROJECT_ROOT/bin/jo" compile --ruby --use-runtime-api ruby \
        --lib "$LIB_OUT" "$src" -o "$rb"; then
        echo "[error] Ruby module compilation failed for $name"
        exit 1
    fi

    ruby "$rb" > "$out" 2>&1

    diff "$out" "$expect" || {
        echo "[error] Ruby module test failed for $name"
        cat "$out"
        exit 1
    }
}

for src in "$DIR"/*.jo; do
    run_case "$(basename "${src%.jo}")"
done

rm -rf "$PROJECT_ROOT/test-tmp-ruby-file" "$PROJECT_ROOT/test-tmp-ruby-pathname"
rm -rf "$DIR/out"
rm -f "$DIR/actual.out" "$DIR"/*.rb

echo "  ✓ All tests passed for $TEST_NAME"
