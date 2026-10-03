#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$PROJECT_ROOT"

# Failures may have backend-specific stack traces; check the complete message
# and location together, as well as the exit status.
check_cases() {
    local backend="$1"
    shift
    local name message line expected
    while IFS='|' read -r name message line; do
        expected="$message (tests/custom/abort-source-location/app.jo:$line)"
        if [[ "$name" == override ]]; then
            expected="$message (generated.jo:99)"
        fi
        if "$@" "$name" > "$WORK/actual" 2>&1; then
            echo "[error] $backend/$name unexpectedly succeeded"
            exit 1
        fi
        if ! grep -Fq "$expected" "$WORK/actual"; then
            echo "[error] $backend/$name: expected $expected"
            cat "$WORK/actual"
            exit 1
        fi
    done <<'CASES'
abort|direct abort|23
assert|direct assertion|24
forward-abort|forwarded abort|25
forward-assert|forwarded assertion|26
override|forwarded assertion|99
placeholder|not implemented|28
forward-placeholder|not implemented|29
tailrec|tailrec abort|30
CASES
    "$@" lazy > "$WORK/actual" 2>&1
    printf 'passed\n' > "$WORK/expected"
    diff -u "$WORK/expected" "$WORK/actual"
    echo "  $backend: all abort location cases passed"
}

check_cases interpreter "$JO" eval --check-tree --source-root "$PROJECT_ROOT" "$DIR/app.jo"
for backend in js python ruby reg stack; do
    "$JO" compile "--$backend" --check-tree --source-root "$PROJECT_ROOT" "$DIR/app.jo" -o "$WORK/app"
    case "$backend" in
        js) check_cases "$backend" node "$WORK/app" ;;
        python) check_cases "$backend" python3 "$WORK/app" ;;
        ruby) check_cases "$backend" ruby "$WORK/app" ;;
        *) check_cases "$backend" "$WORK/app" ;;
    esac
done
