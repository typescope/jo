#!/bin/bash
# Capability injection (JIP 0012) with a factory in a link library: the
# runtime library is passed with --link-lib, so the application cannot import
# it, while --provide and --internal resolve the factory and its configuration
# at link time.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"

BUILD="$DIR/build"
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb

fail() { echo "[error] $1 failed for $TEST_NAME"; [ -f "$DIR/actual.out" ] && cat "$DIR/actual.out"; exit 1; }

echo "  - Building API library"
"$JO" compile --sast "$BUILD/api" "$DIR/api.jo" || fail "API library build"

echo "  - Building runtime library"
"$JO" compile --sast "$BUILD/runtime" "$DIR/runtime.jo" --lib "$BUILD/api" || fail "runtime library build"

INJECT="--lib $BUILD/api --link-lib $BUILD/runtime --provide Api.clock=Runtime.Clock --internal Api.epoch=41"

echo "  - Running with interpreter"
"$JO" eval "$DIR/app.jo" $INJECT > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Interpreter"

echo "  - Building with Python"
"$JO" compile --python "$DIR/app.jo" $INJECT -o "$DIR/app.py" || fail "Python compile"
python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Python"

echo "  - Building with JavaScript"
"$JO" compile --js "$DIR/app.jo" $INJECT -o "$DIR/app.js" || fail "JS compile"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "JS"

echo "  - Building with Ruby"
"$JO" compile --ruby "$DIR/app.jo" $INJECT -o "$DIR/app.rb" || fail "Ruby compile"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Ruby"

rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb
echo "  ✓ All tests passed for $TEST_NAME"
