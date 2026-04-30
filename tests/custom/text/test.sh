#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
BUILD="$DIR/build"
LIB_OUT="$BUILD/lib"

echo "Testing $TEST_NAME"

rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py
mkdir -p "$LIB_OUT"

echo "  - Building text library"
bin/jo compile --sast "$LIB_OUT" "$DIR/Text.jo"

run_case() {
    local name="$1"
    local src="$DIR/$name.jo"
    local expect="$DIR/$name.jo.check"
    local out="$DIR/actual.out"

    echo "  - $name (interpreter)"
    bin/jo eval "$src" --lib "$LIB_OUT" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] Interpreter test failed for $name"
        cat "$out"
        exit 1
    }

    echo "  - $name (register)"
    bin/jo compile --reg "$src" --lib "$LIB_OUT" -o "$DIR/$name.run"
    "$DIR/$name.run" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] Register test failed for $name"
        cat "$out"
        exit 1
    }

    echo "  - $name (stack)"
    bin/jo compile --stack "$src" --lib "$LIB_OUT" -o "$DIR/$name.run"
    "$DIR/$name.run" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] Stack test failed for $name"
        cat "$out"
        exit 1
    }

    echo "  - $name (js)"
    bin/jo compile --js "$src" --lib "$LIB_OUT" -o "$DIR/$name.js"
    node "$DIR/$name.js" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] JavaScript test failed for $name"
        cat "$out"
        exit 1
    }

    echo "  - $name (ruby)"
    bin/jo compile --ruby "$src" --lib "$LIB_OUT" -o "$DIR/$name.rb"
    ruby "$DIR/$name.rb" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] Ruby test failed for $name"
        cat "$out"
        exit 1
    }

    echo "  - $name (python)"
    bin/jo compile --python "$src" --lib "$LIB_OUT" -o "$DIR/$name.py"
    python "$DIR/$name.py" > "$out" 2>&1
    diff "$out" "$expect" || {
        echo "[error] Python test failed for $name"
        cat "$out"
        exit 1
    }
}

for src in "$DIR"/*.jo; do
    name="$(basename "${src%.jo}")"
    if [ "$name" != "Text" ]; then
        run_case "$name"
    fi
done

rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

echo "  ✓ All tests passed for $TEST_NAME"
