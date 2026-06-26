#!/bin/bash
# `jo run` is quiet by default — it runs the app with no [build]/[output]/[run]
# chatter — while `jo run -v` and `jo build` show the build trace.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
cd "$DIR"
rm -rf .build

fail() { echo "[error] $TEST_NAME: $1"; echo "-- stderr --"; cat err.txt 2>/dev/null; rm -rf .build out.txt err.txt; exit 1; }
has_build() { grep -qE '\[build\]|\[output\]|\[run\]' err.txt; }

# 1. jo run (default) — quiet: no build chatter on stderr, app output on stdout.
"$JO" run >out.txt 2>err.txt || fail "jo run failed"
has_build && fail "jo run printed build chatter (should be quiet)"
grep -q "hello from app" out.txt || fail "jo run lost the app output"

# 2. jo run -v — shows the build trace.
"$JO" run -v >/dev/null 2>err.txt || fail "jo run -v failed"
has_build || fail "jo run -v did not show the build trace"

# 3. jo build — stays verbose.
rm -rf .build
"$JO" build >/dev/null 2>err.txt || fail "jo build failed"
has_build || fail "jo build was not verbose"

rm -rf .build out.txt err.txt
echo "  ✓ All tests passed for $TEST_NAME"
