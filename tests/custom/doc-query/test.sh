#!/bin/bash

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
TEST_NAME="$(basename "$DIR")"
REL_DIR="tests/custom/doc-query"
API_FILE="$REL_DIR/api.jo"
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

run_doc() {
    "$PROJECT_ROOT/bin/jo" compile --doc --use-runtime-api python "$@"
}

run_plain_doc() {
    "$PROJECT_ROOT/bin/jo" compile --doc "$@"
}

run_json_doc() {
    "$PROJECT_ROOT/bin/jo" compile --doc --format json --use-runtime-api python "$@"
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

run_json_doc "$API_FILE" > "$DIR/all.json"
run_json_doc --query "DocQueryAPI.*,DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/structural.json"
run_json_doc --query "file:$API_FILE" "$API_FILE" > "$DIR/file.json"
run_json_doc --query "file:$PROJECT_ROOT/$API_FILE" "$API_FILE" > "$DIR/file-absolute.json"
run_json_doc --query "DocQueryAPI.describe" "$API_FILE" > "$DIR/describe.json"
run_json_doc --query "DocQueryAPI.Box.label" "$API_FILE" > "$DIR/exact-member.json"
run_json_doc --include-private --query "DocQueryAPI.hidden" "$API_FILE" > "$DIR/private.json"
run_doc --query "DocQueryAPI.*,DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/query-implies-json.json"
run_doc --lib "$SAST_DIR" --query "DocQueryAPI.*" > "$DIR/lib-query.json"

diff -u "$DIR/all.json.check" "$DIR/all.json"
diff -u "$DIR/structural.json.check" "$DIR/structural.json"
diff -u "$DIR/structural.json.check" "$DIR/file.json"
diff -u "$DIR/structural.json.check" "$DIR/file-absolute.json"
diff -u "$DIR/structural.json.check" "$DIR/query-implies-json.json"
diff -u "$DIR/lib-query.json.check" "$DIR/lib-query.json"
diff -u "$DIR/describe.json.check" "$DIR/describe.json"
diff -u "$DIR/exact-member.json.check" "$DIR/exact-member.json"
diff -u "$DIR/private.json.check" "$DIR/private.json"

expect_fail "$FAIL_LOG" "$PROJECT_ROOT/bin/jo" compile --doc --format yaml "$API_FILE"
rg -q "Option --format must be one of: html, json" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_json_doc --out "$SAST_DIR/out" "$API_FILE"
rg -q -- "--out is only supported with --format html" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_plain_doc --query "NoSuchSymbol"
rg -q "No documentation entries match symbol selector" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_plain_doc --no-stdlib --query "NoSuchSymbol"
rg -q "No documentation entries match symbol selector" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_plain_doc --format json
rg -q "Usage: jo doc" "$FAIL_LOG"

echo "  ✓ All tests passed for $TEST_NAME"
