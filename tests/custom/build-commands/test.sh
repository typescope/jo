#!/bin/bash
# Project [commands] run only with `jo exec <name>`. The bare `jo <name>` form
# reports an unknown command and points at `jo exec`, and a project command
# never shadows a built-in.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
cd "$DIR"

fail() { echo "[error] $TEST_NAME: $1"; echo "-- stdout --"; cat out.txt 2>/dev/null; echo "-- stderr --"; cat err.txt 2>/dev/null; rm -f out.txt err.txt; exit 1; }

# 1. `jo exec <name>` runs the command.
"$JO" exec dev >out.txt 2>err.txt || fail "jo exec dev failed"
grep -q "hi-dev" out.txt || fail "jo exec dev did not run the command"

# 2. Extra arguments are appended to the command.
"$JO" exec dev alpha beta >out.txt 2>err.txt || fail "jo exec dev alpha beta failed"
grep -q "hi-dev alpha beta" out.txt || fail "jo exec dev did not pass args through"

# 3. Extra args are literal data — shell metacharacters are NOT interpreted.
#    `jo exec dev "&& echo INJECTED"` must print the text, not run a second command.
"$JO" exec dev "&& echo INJECTED" >out.txt 2>err.txt || fail "jo exec dev with metachars failed"
grep -q -- "hi-dev && echo INJECTED" out.txt || fail "argument was not passed literally"
grep -qx "INJECTED" out.txt && fail "argument was shell-injected (ran as a second command)"

# 4. `jo <name>` does not run a project command. It reports the unknown
#    command and points at `jo exec`.
if "$JO" dev >out.txt 2>err.txt; then fail "jo dev should have failed"; fi
grep -q "unknown command 'dev'" err.txt || fail "jo dev did not report an unknown command"
grep -q "jo exec dev" err.txt || fail "jo dev did not point at jo exec"
grep -q "hi-dev" out.txt && fail "jo dev ran the command"

# 5. A project command never shadows a built-in: 'help' is defined as a
#    command AND is a built-in; `jo help` prints the usage.
"$JO" help >out.txt 2>err.txt || fail "jo help failed"
grep -q "Usage:" out.txt || fail "jo help did not run the built-in"
grep -q "SHADOWED-HELP" out.txt && fail "jo help ran the command instead of the built-in"

# 6. `jo exec` runs the command even when a built-in shares the name.
"$JO" exec help >out.txt 2>err.txt || fail "jo exec help failed"
grep -q "SHADOWED-HELP" out.txt || fail "jo exec help did not run the command"

# 7. An unknown name fails and lists the project commands, in both forms.
if "$JO" bogus >out.txt 2>err.txt; then fail "jo bogus should have failed"; fi
grep -q "unknown command 'bogus'" err.txt || fail "missing unknown-command error for jo bogus"
grep -q "jo exec <name>" err.txt || fail "jo bogus did not point at jo exec"
grep -q "dev, fails, help" err.txt || fail "jo bogus did not list the project commands"

if "$JO" exec bogus >out.txt 2>err.txt; then fail "jo exec bogus should have failed"; fi
grep -q "unknown command 'bogus'" err.txt || fail "missing unknown-command error for jo exec bogus"
grep -q "Defined commands: dev, fails, help" err.txt || fail "jo exec bogus did not list the project commands"

# 8. `jo exec` without a name fails.
if "$JO" exec >out.txt 2>err.txt; then fail "jo exec without a name should have failed"; fi
grep -q "requires a command name" err.txt || fail "jo exec without a name gave no error"

# 9. A command's exit status propagates, so shell `&&`/`||`/`set -e` and CI see
#    failures. `fails` runs then exits 3; jo must exit 3 too.
"$JO" exec fails >out.txt 2>err.txt
code=$?
[ "$code" -eq 3 ] || fail "exit status not propagated (expected 3, got $code)"
grep -q "before-failure" out.txt || fail "command ran but its output was lost"

# 10. Shell chaining: the `&&` here is the invoking shell's, not jo's. jo exits 0
#     after a successful command, so the right-hand side runs.
{ "$JO" exec dev && echo success; } >out.txt 2>err.txt || fail "jo exec dev && echo success failed"
grep -q "hi-dev" out.txt  || fail "jo exec dev output missing in the && chain"
grep -q "success" out.txt || fail "&& did not run after a successful jo exec dev"

# 10b. And it short-circuits: a failing command's nonzero exit stops the chain.
{ "$JO" exec fails && echo should-not-run; } >out.txt 2>err.txt
grep -q "before-failure" out.txt   || fail "jo exec fails did not run in the && chain"
grep -q "should-not-run" out.txt   && fail "&& ran the RHS despite jo exec fails exiting nonzero"

rm -f out.txt err.txt
echo "  ✓ All tests passed for $TEST_NAME"
