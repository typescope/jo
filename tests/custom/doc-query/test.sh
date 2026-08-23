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
PORTABLE_DOC_DIR="$SAST_DIR-doc"
PORTABLE_JSON="$DIR/portable.json"
OUTSIDE_SOURCE="$SAST_DIR-source.jo"
OUTSIDE_SAST_DIR="$SAST_DIR-outside"

cleanup() {
    rm -f "$DIR"/*.json
    rm -rf "$SAST_DIR" "$SAST_LOG" "$FAIL_LOG" "$PORTABLE_DOC_DIR" "$OUTSIDE_SOURCE" "$OUTSIDE_SAST_DIR"
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

# Without an explicit root, the compiler uses its current working directory.
run_query --query "DocQueryAPI.describe" --fields loc "$PROJECT_ROOT/$API_FILE" > "$PORTABLE_JSON"
grep -q -- '"file": "tests/custom/doc-query/api.jo"' "$PORTABLE_JSON"
if grep -F -q -- "$PROJECT_ROOT" "$PORTABLE_JSON"; then
    echo "Absolute project path leaked with the default source root"
    exit 1
fi

# Absolute input paths must not leak into published SAST metadata or HTML docs.
"$PROJECT_ROOT/bin/jo" compile --sast "$SAST_DIR" --source-root "$PROJECT_ROOT" \
    --use-runtime-api python "$PROJECT_ROOT/$API_FILE" > "$SAST_LOG"

# Direct-source query locations use the same published path mapping as SAST.
run_query --source-root "$PROJECT_ROOT" --query "DocQueryAPI.describe" \
    --fields loc "$PROJECT_ROOT/$API_FILE" > "$PORTABLE_JSON"
grep -q -- '"file": "tests/custom/doc-query/api.jo"' "$PORTABLE_JSON"
if grep -F -q -- "$PROJECT_ROOT" "$PORTABLE_JSON"; then
    echo "Absolute project path leaked into direct-source query output"
    exit 1
fi

run_query --lib "$SAST_DIR" --query "DocQueryAPI.describe" --fields loc > "$PORTABLE_JSON"
grep -q -- '"file": "tests/custom/doc-query/api.jo"' "$PORTABLE_JSON"
if grep -R -F -q -- "$PROJECT_ROOT" "$SAST_DIR"; then
    echo "Absolute project path leaked into SAST output"
    exit 1
fi

# Sources outside the root retain only their basename.
cp "$PROJECT_ROOT/$API_FILE" "$OUTSIDE_SOURCE"
"$PROJECT_ROOT/bin/jo" compile --sast "$OUTSIDE_SAST_DIR" --source-root "$PROJECT_ROOT" \
    --use-runtime-api python "$OUTSIDE_SOURCE" > "$SAST_LOG"
run_query --lib "$OUTSIDE_SAST_DIR" --query "DocQueryAPI.describe" --fields loc > "$PORTABLE_JSON"
grep -q -- '"file": "jo-doc-query-sast-[0-9]*-source.jo"' "$PORTABLE_JSON"
if grep -R -F -q -- "$(dirname "$OUTSIDE_SOURCE")" "$OUTSIDE_SAST_DIR"; then
    echo "Containing directory of outside source leaked into SAST output"
    exit 1
fi

run_query --source-root "$PROJECT_ROOT" --query "DocQueryAPI.describe" \
    --fields loc "$OUTSIDE_SOURCE" > "$PORTABLE_JSON"
grep -q -- '"file": "jo-doc-query-sast-[0-9]*-source.jo"' "$PORTABLE_JSON"
if grep -F -q -- "$(dirname "$OUTSIDE_SOURCE")" "$PORTABLE_JSON"; then
    echo "Containing directory of outside source leaked into direct-source query output"
    exit 1
fi

"$PROJECT_ROOT/bin/jo" compile --doc --source-root "$PROJECT_ROOT" \
    --use-runtime-api python --out "$PORTABLE_DOC_DIR" "$OUTSIDE_SOURCE" > /dev/null
grep -q -- '"file": "jo-doc-query-sast-[0-9]*-source.jo"' "$PORTABLE_DOC_DIR/data.js"
if grep -R -F -q -- "$(dirname "$OUTSIDE_SOURCE")" "$PORTABLE_DOC_DIR"; then
    echo "Containing directory of outside source leaked into documentation"
    exit 1
fi

"$PROJECT_ROOT/bin/jo" compile --doc --source-root "$PROJECT_ROOT" \
    --use-runtime-api python --out "$PORTABLE_DOC_DIR" "$PROJECT_ROOT/$API_FILE" > /dev/null
grep -q -- '"file": "tests/custom/doc-query/api.jo"' "$PORTABLE_DOC_DIR/data.js"
if grep -R -F -q -- "$PROJECT_ROOT" "$PORTABLE_DOC_DIR"; then
    echo "Absolute project path leaked into documentation"
    exit 1
fi

run_query --query "DocQueryAPI.*,DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/structural.json"
run_query --query "file:$API_FILE" "$API_FILE" "$EXTRA_FILE" > "$DIR/file.json"
run_query --query "file:$PROJECT_ROOT/$API_FILE" "$API_FILE" "$EXTRA_FILE" > "$DIR/file-absolute.json"
run_query --query "DocQueryAPI.describe" "$API_FILE" > "$DIR/describe.json"
run_query --query "DocQueryAPI.describe" --fields loc,name "$API_FILE" > "$DIR/fields.json"
run_query --query "DocQueryAPI.Box.label" "$API_FILE" > "$DIR/exact-member.json"
run_query --query "DocQueryAPI.FileLike.readText" "$API_FILE" > "$DIR/interface-member.json"
run_query --query "DocQueryAPI.Box.name" "$API_FILE" > "$DIR/exact-field.json"
run_query --query "DocQueryCompanion.Value.label" "$COMPANION_FILE" > "$DIR/companion-member.json"
run_query --query "DocQueryCompanion.TaggedValue" "$COMPANION_FILE" > "$DIR/class-views.json"
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
diff -u "$DIR/fields.json.check" "$DIR/fields.json"
diff -u "$DIR/exact-member.json.check" "$DIR/exact-member.json"
diff -u "$DIR/interface-member.json.check" "$DIR/interface-member.json"
diff -u "$DIR/exact-field.json.check" "$DIR/exact-field.json"
diff -u "$DIR/companion-member.json.check" "$DIR/companion-member.json"
diff -u "$DIR/class-views.json.check" "$DIR/class-views.json"

expect_fail "$FAIL_LOG" run_plain_query --query "NoSuchSymbol"
grep -q -- "No documentation entries match symbol selector" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_plain_query --no-stdlib --query "NoSuchSymbol"
grep -q -- "No documentation entries match symbol selector" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_query --query "DocQueryAPI.describe" --fields unknown "$API_FILE"
grep -q -- "Unknown query field: unknown" "$FAIL_LOG"
grep -q -- "Available fields: name,kind,signature,loc,visibility,flags,annotations,doc" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_query --query "DocQueryAPI.describe" --fields views "$API_FILE"
grep -q -- "Unknown query field: views" "$FAIL_LOG"

expect_fail "$FAIL_LOG" run_query --query "DocQueryAPI.describe" --fields , "$API_FILE"
grep -q -- "Option --fields requires at least one field" "$FAIL_LOG"
grep -q -- "Available fields: name,kind,signature,loc,visibility,flags,annotations,doc" "$FAIL_LOG"

echo "  ✓ All tests passed for $TEST_NAME"
