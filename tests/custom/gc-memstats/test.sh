#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run

fail() {
    echo "[error] $TEST_NAME: $1"
    cat "$DIR/actual.err"
    exit 1
}

# With names: `remember` promotes 5000 nodes by a store into the array of
# `main`, `List.append` promotes 3000 for `note`, the region of `accumulate`
# holds its 5000 nodes at once, and `main` allocates 3000 nodes directly.
echo "  - Building with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/app.jo" -o "$DIR/app.run" || exit 1
JO_MEMSTATS=1 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
grep -q '^jo-mem summary peak-rss-kb=[0-9]* allocated=' "$DIR/actual.err" || fail "no summary"
grep -q '^jo-mem promoted kind=store count=5000 fun=memapp.remember at=.*app.jo:[0-9]* into=jo.Array caller=memapp.main$' "$DIR/actual.err" || fail "no store row for remember"
awk '/^jo-mem region / && /fun=memapp.accumulate / { split($3, a, "="); if (a[2] >= 4960) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no region row for accumulate"
grep -q '^jo-mem promoted kind=store count=[0-9]* fun=jo\.mutable\.List\$append at=.* into=jo\.Array caller=memapp\.note$' "$DIR/actual.err" || fail "no store row for append called by note"
awk '/^jo-mem to-runtime / { split($3, a, "="); if (a[1] == "direct" && a[2] >= 3000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no direct allocations in main"
grep -q '^jo-mem hint store: ' "$DIR/actual.err" || fail "no store hint"
grep -q '^jo-mem hint library: ' "$DIR/actual.err" || fail "no library hint"
grep -q '^jo-mem hint main: ' "$DIR/actual.err" || fail "no main hint"
grep -q '^jo-mem hint region: ' "$DIR/actual.err" || fail "no region hint"

echo "  - Running without JO_MEMSTATS"
"$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
[ ! -s "$DIR/actual.err" ] || fail "statistics printed without JO_MEMSTATS"

echo "  - Building without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/app.jo" -o "$DIR/app.run" || exit 1
JO_MEMSTATS=1 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
grep -q '^jo-mem summary ' "$DIR/actual.err" || fail "no summary"
grep -q '^jo-mem hint rebuild with --mem-profile' "$DIR/actual.err" || fail "no hint to rebuild"
grep -q '^jo-mem hint main: ' "$DIR/actual.err" || fail "no main hint without names"
! grep -q '^jo-mem promoted' "$DIR/actual.err" || fail "rows without --mem-profile"

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
