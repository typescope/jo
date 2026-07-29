#!/bin/bash

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
TEST_NAME="$(basename "$DIR")"
REL_DIR="tests/custom/doc-query"
API_FILE="$REL_DIR/api.jo"
EXTRA_FILE="$REL_DIR/extra.jo"
COMPANION_FILE="$REL_DIR/companion.jo"
SAST_DIR="${TMPDIR:-/tmp}/jo-doc-query-sast-$$"
SAST_LOG="$SAST_DIR.log"
FAIL_LOG="$DIR/fail.log"

cleanup() {
    rm -f "$DIR"/*.json
    rm -rf "$SAST_DIR" "$SAST_LOG" "$FAIL_LOG"
}

finish() {
    local status=$?
    if [ "$status" -eq 0 ]; then
        cleanup
    else
        echo "  failed; temporary JSON/log files were left for inspection"
    fi
}

trap finish EXIT

echo "Testing $TEST_NAME"

cleanup

run_query() {
    "$PROJECT_ROOT/bin/jo" compile --use-runtime-api python "$@"
}

run_plain_query() {
    "$PROJECT_ROOT/bin/jo" compile "$@"
}

expect_fail() {
    local out="$1"
    shift
    if "$@" > "$out" 2>&1; then
        echo "Expected command to fail: $*"
        exit 1
    fi
}

cd "$PROJECT_ROOT"

"$PROJECT_ROOT/bin/jo" compile --sast "$SAST_DIR" --use-runtime-api python "$API_FILE" > "$SAST_LOG"

run_query --query "DocQueryAPI.*,DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/structural.json"
run_query --query "file:$API_FILE" "$API_FILE" "$EXTRA_FILE" > "$DIR/file.json"
run_query --query "file:$PROJECT_ROOT/$API_FILE" "$API_FILE" "$EXTRA_FILE" > "$DIR/file-absolute.json"
run_query --query "DocQueryAPI.describe" "$API_FILE" > "$DIR/describe.json"
run_query --query "DocQueryAPI.Box.label" "$API_FILE" > "$DIR/exact-member.json"
run_query --query "DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/interface-member.json"
run_query --query "DocQueryAPI.Box.name" "$API_FILE" > "$DIR/exact-field.json"
run_query --query "DocQueryCompanion.Value.label" "$COMPANION_FILE" > "$DIR/companion-member.json"
run_query --query "DocQueryAPI,DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/query-implies-json.json"
run_query --lib "$SAST_DIR" --query "DocQueryAPI" > "$DIR/lib-query.json"
run_query --lib "$SAST_DIR" --query "DocQueryAPI.Box.label" > "$DIR/lib-exact-member.json"
run_query --lib "$SAST_DIR" --query "file:api.jo" > "$DIR/lib-file-query.json"

diff -u "$DIR/structural.json.check" "$DIR/structural.json"
diff -u "$DIR/structural.json.check" "$DIR/file.json"
diff -u "$DIR/structural.json.check" "$DIR/file-absolute.json"
diff -u "$DIR/structural.json.check" "$DIR/query-implies-json.json"
diff -u "$DIR/lib-query.json.check" "$DIR/lib-query.json"
diff -u "$DIR/lib-query.json.check" "$DIR/lib-file-query.json"
diff -u "$DIR/lib-exact-member.json.check" "$DIR/lib-exact-member.json"
diff -u "$DIR/describe.json.check" "$DIR/describe.json"
diff -u "$DIR/exact-member.json.check" "$DIR/exact-member.json"
diff -u "$DIR/interface-member.json.check" "$DIR/interface-member.json"
diff -u "$DIR/exact-field.json.check" "$DIR/exact-field.json"
diff -u "$DIR/companion-member.json.check" "$DIR/companion-member.json"

expect_fail "$FAIL_LOG" run_plain_query --query "NoSuchSymbol"
grep -q -- "No documentation entries match symbol selector" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_plain_query --no-stdlib --query "NoSuchSymbol"
grep -q -- "No documentation entries match symbol selector" "$FAIL_LOG"

echo "  ✓ All tests passed for $TEST_NAME"
