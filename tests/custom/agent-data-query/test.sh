#!/usr/bin/env bash
# The data-query templates: three agent frameworks share one confined API and
# trusted runtime, a bridge compiles agent-supplied Jo against them, and the
# compiled program is confined to the user the host selects.
#
# - the data files, the bridge, and prepare.sh are identical across the
#   LangChain and OpenAI Agents templates, so the bridge is checked once
# - prepare.sh builds every template with the compiler under test
# - generated code cannot name the runtime or the FFI, cannot widen the entry
#   point's capabilities, and reads and writes only the selected user's rows
# - invalid updates are rejected before anything is written
# - the Harpe guest built from sandbox/jo.toml is confined the same way

set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"
TEMPLATES="$PROJECT_ROOT/templates"

echo "Testing $TEST_NAME"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/jo-agent-data-query.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
LOG="$TMP_ROOT/log"

fail() { echo "[error] $1 failed for $TEST_NAME"; [ -f "$LOG" ] && cat "$LOG"; exit 1; }

# The templates invoke `jo` from PATH, as a user's copy does. Put the compiler
# under test first, so that an installed `jo` is never picked up instead.
[ -x "$JO" ] || fail "finding the compiler $JO"
mkdir "$TMP_ROOT/bin"
printf '#!/bin/sh\nexec "%s" "$@"\n' "$JO" > "$TMP_ROOT/bin/jo"
chmod +x "$TMP_ROOT/bin/jo"
export PATH="$TMP_ROOT/bin:$PATH"

echo "  - Checking that the templates share their data files and bridge"
for file in DatabaseAPI.jo Runtime.jo os.jo sqlite3.jo seed.sql; do
  cmp "$TEMPLATES/langchain-data-query/data/$file" "$TEMPLATES/openai-agents-data-query/data/$file" \
    || fail "openai-agents-data-query/data/$file differs"
  cmp "$TEMPLATES/langchain-data-query/data/$file" "$TEMPLATES/harpe-data-query/data/$file" \
    || fail "harpe-data-query/data/$file differs"
done
for file in jo_bridge.py prepare.sh; do
  cmp "$TEMPLATES/langchain-data-query/$file" "$TEMPLATES/openai-agents-data-query/$file" \
    || fail "openai-agents-data-query/$file differs"
done

# Copies a template into a fresh directory and prepares it there.
prepare() {
  mkdir "$TMP_ROOT/$1"
  cp -a "$TEMPLATES/$1/." "$TMP_ROOT/$1/"
  (cd "$TMP_ROOT/$1" && bash prepare.sh) > "$LOG" 2>&1 || fail "prepare.sh of $1"
}

echo "  - Checking the bridge"
prepare langchain-data-query
(cd "$TMP_ROOT/langchain-data-query" && PYTHONPATH=. python3 "$DIR/check_bridge.py") > "$LOG" 2>&1 \
  || fail "bridge checks"

echo "  - Checking the Harpe guest"
prepare harpe-data-query
HARPE="$TMP_ROOT/harpe-data-query"
GUEST="$(find "$HARPE/sandbox/.build/guest" -path '*/target/guest.py' -print -quit)"
[ -n "$GUEST" ] || fail "finding the compiled guest"

# Harpe supplies the user outside the model's tool input, through the
# environment that run.sh requires.
for user in 1 2; do
  (cd "$HARPE" && HARPE_USER_ID=$user HARPE_DB_PATH="$HARPE/out/database.db" bash sandbox/run.sh "$GUEST") \
    > "$TMP_ROOT/harpe-$user" 2>&1 || { cp "$TMP_ROOT/harpe-$user" "$LOG"; fail "running the guest as user $user"; }
done
grep -q 'Q4 Budget Report' "$TMP_ROOT/harpe-1" || fail "user 1 sees own document"
grep -q 'Technical Design' "$TMP_ROOT/harpe-1" && fail "user 1 must not see user 2's document"
grep -q 'Technical Design' "$TMP_ROOT/harpe-2" || fail "user 2 sees own document"
grep -q 'Q4 Budget Report' "$TMP_ROOT/harpe-2" && fail "user 2 must not see user 1's document"

echo "  ✓ All tests passed for $TEST_NAME"
