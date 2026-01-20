#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Test arguments
ARGS="hello world 123"

# Clean up previous build artifacts
rm -rf "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

# Test with interpreter (arguments come after --)
echo "  - Running with interpreter"
bin/jo run "$DIR/app.jo" $ARGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Test with register machine (default backend)
echo "  - Building with register machine"
bin/jo build -reg "$DIR/app.jo" -o "$DIR/app.run"
"$DIR/app.run" $ARGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo build -stack "$DIR/app.jo" -o "$DIR/app.run"
"$DIR/app.run" $ARGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo build -js "$DIR/app.jo" -o "$DIR/app.js"
node "$DIR/app.js" $ARGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Test with Ruby
echo "  - Building with Ruby"
bin/jo build -ruby "$DIR/app.jo" -o "$DIR/app.rb"
ruby "$DIR/app.rb" $ARGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Clean up
rm -rf "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

echo "  ✓ All tests passed for $TEST_NAME"
