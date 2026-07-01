#!/bin/bash
# Project [commands]: `jo <name>` runs a defined command (built-ins win), while
# `jo exec <name>` always runs the command, bypassing built-ins.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
cd "$DIR"

fail() { echo "[error] $TEST_NAME: $1"; echo "-- stdout --"; cat out.txt 2>/dev/null; echo "-- stderr --"; cat err.txt 2>/dev/null; rm -f out.txt err.txt; exit 1; }

# 1. `jo <name>` falls through to a [commands] entry.
"$JO" dev >out.txt 2>err.txt || fail "jo dev failed"
grep -q "hi-dev" out.txt || fail "jo dev did not run the command"

# 2. Extra arguments are appended to the command.
"$JO" dev alpha beta >out.txt 2>err.txt || fail "jo dev alpha beta failed"
grep -q "hi-dev alpha beta" out.txt || fail "jo dev did not pass args through"

# 3. `jo exec <name>` runs the command.
"$JO" exec dev >out.txt 2>err.txt || fail "jo exec dev failed"
grep -q "hi-dev" out.txt || fail "jo exec dev did not run the command"

# 4. Built-ins win: 'help' is defined as a command AND is a built-in;
#    `jo help` must run the built-in (print usage), not the command.
"$JO" help >out.txt 2>err.txt || fail "jo help failed"
grep -q "Usage:" out.txt || fail "jo help did not run the built-in"
grep -q "SHADOWED-HELP" out.txt && fail "jo help ran the command instead of the built-in"

# 5. `jo exec` forces the command even when a built-in shares the name.
"$JO" exec help >out.txt 2>err.txt || fail "jo exec help failed"
grep -q "SHADOWED-HELP" out.txt || fail "jo exec help did not run the command"

# 6. An unknown command fails and lists the defined commands.
if "$JO" bogus >out.txt 2>err.txt; then fail "jo bogus should have failed"; fi
grep -q "unknown command 'bogus'" err.txt || fail "missing unknown-command error"
grep -q "Defined commands:" err.txt || fail "did not list defined commands"

rm -f out.txt err.txt
echo "  ✓ All tests passed for $TEST_NAME"
