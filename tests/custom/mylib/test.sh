#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Clean up previous build artifacts
rm -rf "$DIR/build" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

# Build the library
echo "  - Building library"
bin/jo build-lib "$DIR/lib.stk" -d "$DIR/build"

# Test with interpreter
echo "  - Running with interpreter"
bin/jo run "$DIR/app.stk" -lib "$DIR/build" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo build -reg "$DIR/app.stk" -lib "$DIR/build" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo build -stack "$DIR/app.stk" -lib "$DIR/build" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo build -js "$DIR/app.stk" -lib "$DIR/build" -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$DIR/build" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

echo "  ✓ All tests passed for $TEST_NAME"
