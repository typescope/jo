#!/bin/bash
# Capability injection with a factory that uses the FFI: the runtime library
# is compiled with --use-runtime-api python and passed with --link-lib. The
# factory creates the capability from the process environment, the
# application receives it through --provide, and the application cannot name
# the factory itself.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"

BUILD="$DIR/build"
rm -rf "$BUILD" "$DIR/actual.out" "$DIR/app.py" "$DIR/direct.py"

fail() { echo "[error] $1 failed for $TEST_NAME"; [ -f "$DIR/actual.out" ] && cat "$DIR/actual.out"; exit 1; }

echo "  - Building API library"
"$JO" compile --sast "$BUILD/api" "$DIR/api.jo" || fail "API library build"

echo "  - Building runtime library with the Python FFI"
"$JO" compile --sast "$BUILD/runtime" --use-runtime-api python "$DIR/runtime.jo" --lib "$BUILD/api" || fail "runtime library build"

INJECT=(--lib "$BUILD/api" --link-lib "$BUILD/runtime" --provide Api.env=Runtime.Env --internal 'Api.prefix="env:"')

echo "  - Building and running the application"
"$JO" compile --python "$DIR/app.jo" "${INJECT[@]}" -o "$DIR/app.py" || fail "Python compile"
INJECT_FFI_NAME=world python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Python"

echo "  - Checking that the application cannot call the factory"
if "$JO" compile --python "$DIR/direct.jo" "${INJECT[@]}" -o "$DIR/direct.py" > "$DIR/actual.out" 2>&1; then
  fail "direct factory call was accepted"
fi
grep -q "Undefined term name Runtime" "$DIR/actual.out" || fail "direct factory call diagnostic"

rm -rf "$BUILD" "$DIR/actual.out" "$DIR/app.py" "$DIR/direct.py"
echo "  ✓ All tests passed for $TEST_NAME"
