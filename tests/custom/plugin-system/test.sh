#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"

echo "Testing $TEST_NAME"

# Build directory
BUILD="$DIR/build"

# Clean up previous build artifacts
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

# Build the framework library
echo "  - Building framework library"
bin/jo build-lib "$DIR/framework.jo" -d "$BUILD/framework"

# Build the plugin library (depends on framework for type checking)
echo "  - Building plugin library"
bin/jo build-lib "$DIR/plugin.jo" -lib "$BUILD/framework" -d "$BUILD/plugin"

# Link flags to wire plugin implementations to framework extension points
LINK_FLAGS="-link Framework.getName=Plugin.getName \
            -link Framework.getVersion=Plugin.getVersion \
            -link Framework.initialize=Plugin.initialize \
            -link Framework.execute=Plugin.execute"

# Test with interpreter
echo "  - Running with interpreter"
bin/jo run "$DIR/app.jo" -lib "$BUILD/framework:$BUILD/plugin" $LINK_FLAGS > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Interpreter test failed for $TEST_NAME"
    exit 1
}

# Test with register machine
echo "  - Building with register machine"
bin/jo build -reg "$DIR/app.jo" -lib "$BUILD/framework:$BUILD/plugin" $LINK_FLAGS -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Register machine test failed for $TEST_NAME"
    exit 1
}

# Test with stack machine
echo "  - Building with stack machine"
bin/jo build -stack "$DIR/app.jo" -lib "$BUILD/framework:$BUILD/plugin" $LINK_FLAGS -o "$DIR/app.run"
"$DIR/app.run" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] Stack machine test failed for $TEST_NAME"
    exit 1
}

# Test with JavaScript
echo "  - Building with JavaScript"
bin/jo build -js "$DIR/app.jo" -lib "$BUILD/framework:$BUILD/plugin" $LINK_FLAGS -o "$DIR/app.js"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] JavaScript test failed for $TEST_NAME"
    exit 1
}

# Clean up
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/*.run "$DIR"/*.js

echo "  ✓ All tests passed for $TEST_NAME"
