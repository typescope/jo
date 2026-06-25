#!/bin/bash
# A zero-source app: the entry point is linked in from a library, so the
# compiler must accept an empty source list (only --lib + --link jo.main).

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="$PROJECT_ROOT/bin/jo"

echo "Testing $TEST_NAME"

BUILD="$DIR/build"
rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb

fail() { echo "[error] $1 failed for $TEST_NAME"; [ -f "$DIR/actual.out" ] && cat "$DIR/actual.out"; exit 1; }

# Build the library that provides the entry point.
"$JO" compile --sast "$BUILD/lib" "$DIR/lib.jo" || fail "library build"

# Python — no source files; entry linked from the library.
"$JO" compile --python --lib "$BUILD/lib" --link jo.main=Lib.entry -o "$DIR/app.py" || fail "Python compile"
python3 "$DIR/app.py" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Python"

# JavaScript
"$JO" compile --js --lib "$BUILD/lib" --link jo.main=Lib.entry -o "$DIR/app.js" || fail "JS compile"
node "$DIR/app.js" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "JS"

# Ruby
"$JO" compile --ruby --lib "$BUILD/lib" --link jo.main=Lib.entry -o "$DIR/app.rb" || fail "Ruby compile"
ruby "$DIR/app.rb" > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "Ruby"

rm -rf "$BUILD" "$DIR/actual.out" "$DIR"/app.py "$DIR"/app.js "$DIR"/app.rb
echo "  ✓ All tests passed for $TEST_NAME"
