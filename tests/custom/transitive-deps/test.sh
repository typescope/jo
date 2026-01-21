#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Build directories
BUILD_CORE="$DIR/build-core"
BUILD_VALIDATION="$DIR/build-validation"
BUILD_PROCESSOR="$DIR/build-processor"

# Library path for applications (colon-separated, in dependency order)
LIBS="$BUILD_CORE:$BUILD_VALIDATION:$BUILD_PROCESSOR"

# Clean up previous build artifacts
rm -rf "$BUILD_CORE" "$BUILD_VALIDATION" "$BUILD_PROCESSOR" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

# Build core library (no dependencies)
echo "  - Building core library"
bin/jo build-lib "$DIR/core.jo" -d "$BUILD_CORE"

# Build validation library (depends on core)
echo "  - Building validation library"
bin/jo build-lib "$DIR/validation.jo" -lib "$BUILD_CORE" -d "$BUILD_VALIDATION"

# Build processor library (depends on core and validation)
echo "  - Building processor library"
bin/jo build-lib "$DIR/processor.jo" -lib "$BUILD_CORE:$BUILD_VALIDATION" -d "$BUILD_PROCESSOR"

# Test with interpreter
echo "  - Running with interpreter"
bin/jo run "$DIR/app.jo" -lib "$LIBS" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo build -reg "$DIR/app.jo" -lib "$LIBS" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo build -stack "$DIR/app.jo" -lib "$LIBS" -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo build -js "$DIR/app.jo" -lib "$LIBS" -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Test with Ruby
echo "  - Building with Ruby"
bin/jo build -ruby "$DIR/app.jo" -lib "$LIBS" -o "$DIR/app.rb"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Ruby test failed for $TEST_NAME"
    exit 1
}

# Test with Python
echo "  - Building with Python"
bin/jo build -python "$DIR/app.jo" -lib "$LIBS" -o "$DIR/app.py"
python "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Python test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$DIR"/build-* "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js "$DIR"/*.rb "$DIR"/*.py

echo "  ✓ All tests passed for $TEST_NAME"
