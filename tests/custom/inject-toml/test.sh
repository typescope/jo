#!/bin/bash
# Capability injection declared in jo.toml: --provide and
# --internal travel through compile-options, with a TOML literal string
# carrying a Jo string literal without escapes.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
cd "$DIR"
rm -rf .build out.txt err.txt

fail() { echo "[error] $TEST_NAME: $1"; echo "-- stdout --"; cat out.txt 2>/dev/null; echo "-- stderr --"; cat err.txt 2>/dev/null; rm -rf .build out.txt err.txt jo.lock; exit 1; }

"$JO" run >out.txt 2>err.txt || fail "jo run failed"
diff out.txt expect.check || fail "jo run output differs from expect.check"

rm -rf .build out.txt err.txt jo.lock
echo "  ✓ All tests passed for $TEST_NAME"
