#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Clean up previous build artifacts
rm -rf "$DIR/build-math" "$DIR/build-graphics" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

# Build math library
echo "  - Building math library"
bin/jo build-lib "$DIR/math.stk" -d "$DIR/build-math"

# Build graphics library (depends on math)
echo "  - Building graphics library"
bin/jo build-lib "$DIR/graphics.stk" -lib "$DIR/build-math" -d "$DIR/build-graphics"

# Test with interpreter (use colon-separated library paths)
echo "  - Running with interpreter"
bin/jo run "$DIR/app.stk" -lib "$DIR/build-math:$DIR/build-graphics" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo build -reg "$DIR/app.stk" -lib "$DIR/build-math:$DIR/build-graphics" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo build -stack "$DIR/app.stk" -lib "$DIR/build-math:$DIR/build-graphics" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo build -js "$DIR/app.stk" -lib "$DIR/build-math:$DIR/build-graphics" -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$DIR/build-math" "$DIR/build-graphics" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

echo "  ✓ All tests passed for $TEST_NAME"
