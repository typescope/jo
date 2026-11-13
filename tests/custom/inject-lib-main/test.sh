#!/bin/bash
# Capability injection for an entry point that lives in a library: no source
# file is compiled, --link names main in a --lib library, and --provide and
# --internal bind its capabilities. A library main that receives an internal
# parameter is rejected like one compiled from source.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"

BUILD="$DIR/build"
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb

fail() { echo "[error] $1 failed for $TEST_NAME"; [ -f "$DIR/actual.out" ] && cat "$DIR/actual.out"; exit 1; }

echo "  - Building libraries"
"$JO" compile --sast "$BUILD/api" "$DIR/api.jo" || fail "API library build"
"$JO" compile --sast "$BUILD/runtime" "$DIR/runtime.jo" --lib "$BUILD/api" || fail "runtime library build"
"$JO" compile --sast "$BUILD/app" "$DIR/app.jo" --lib "$BUILD/api" || fail "app library build"
"$JO" compile --sast "$BUILD/bad" "$DIR/bad.jo" --lib "$BUILD/api" || fail "bad library build"

BIND="--link-lib $BUILD/runtime --provide Api.clock=Runtime.Clock --internal Api.epoch=41"
INJECT="--lib $BUILD/api --lib $BUILD/app --link jo.main=App.main $BIND"

echo "  - Building with Python"
"$JO" compile --python $INJECT -o "$DIR/app.py" || fail "Python compile"
python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Python"

echo "  - Building with JavaScript"
"$JO" compile --js $INJECT -o "$DIR/app.js" || fail "JS compile"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "JS"

echo "  - Building with Ruby"
"$JO" compile --ruby $INJECT -o "$DIR/app.rb" || fail "Ruby compile"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Ruby"

echo "  - Rejecting a library main that receives an internal parameter"
if "$JO" compile --python --lib "$BUILD/api" --lib "$BUILD/bad" --link jo.main=Bad.main $BIND -o "$DIR/app.py" > "$DIR/actual.out" 2>&1; then
  fail "negative compile unexpectedly succeeded"
fi
grep -q "Api.epoch is not provided" "$DIR/actual.out" || fail "negative compile error message"

rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb
echo "  ✓ All tests passed for $TEST_NAME"
