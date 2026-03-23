#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Build directories
BUILD_MATH="$DIR/build-math"
BUILD_GRAPHICS="$DIR/build-graphics"

# Library path for applications (colon-separated, in dependency order)
LIBS="$BUILD_MATH:$BUILD_GRAPHICS"

# Clean up previous build artifacts
rm -rf "$BUILD_MATH" "$BUILD_GRAPHICS" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

# Build math library
echo "  - Building math library"
bin/jo compile --sast "$DIR/math.jo" -d "$BUILD_MATH"

# Build graphics library (depends on math)
echo "  - Building graphics library"
bin/jo compile --sast "$DIR/graphics.jo" --lib "$BUILD_MATH" -d "$BUILD_GRAPHICS"

# Test with interpreter
echo "  - Running with interpreter"
bin/jo eval "$DIR/app.jo" --lib "$LIBS" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo compile --reg "$DIR/app.jo" --lib "$LIBS" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo compile --stack "$DIR/app.jo" --lib "$LIBS" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo compile --js "$DIR/app.jo" --lib "$LIBS" -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Test with Ruby
echo "  - Building with Ruby"
bin/jo compile --ruby "$DIR/app.jo" --lib "$LIBS" -o "$DIR/app.rb"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby test failed for $TEST_NAME"
    exit 1
}

# Test with Python
echo "  - Building with Python"
bin/jo compile --python "$DIR/app.jo" --lib "$LIBS" -o "$DIR/app.py"
python "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Python test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$BUILD_MATH" "$BUILD_GRAPHICS" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

echo "  ✓ All tests passed for $TEST_NAME"
