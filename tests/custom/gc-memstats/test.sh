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
! grep -q '^jo-mem growth' "$DIR/actual.err" || fail "growth report for a bounded runtime region"

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

# Growth reports: `handle` promotes a node per call by a store into the state
# of `main`, 300000 in all. The runtime region reaches 1024, 2048 and 4096
# segments of 62 objects, about 63000, 127000 and 254000 objects, so there are
# 3 reports. The next one would need 8192 segments.
echo "  - Building server.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/server.jo" -o "$DIR/server.run" || exit 1
JO_MEMSTATS=1 "$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "299999" ] || fail "unexpected output of server.jo"
[ "$(grep -c '^jo-mem growth runtime-region=[0-9]* peak-rss-kb=[0-9]* direct=[0-9]* store=[0-9]* trace=[0-9]* return=[0-9]*$' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth reports"
awk '/^jo-mem summary/ { s = NR } /^jo-mem growth/ { g = NR } END { exit !(g > 0 && g < s) }' "$DIR/actual.err" || fail "growth reports after the summary"
[ "$(grep -c '^jo-mem growth-promoted kind=store count=[0-9]* fun=memsrv.handle at=.*server.jo:[0-9]* into=memsrv.State caller=memsrv.main$' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth-promoted rows for handle"
[ "$(grep -c '^jo-mem growth-promoted' "$DIR/actual.err")" = 3 ] || fail "unexpected growth-promoted rows"
awk '/^jo-mem growth-promoted / { split($4, a, "="); n++; sum += a[2]; if (a[2] >= 150000) bad = 1 } END { exit !(n == 3 && !bad && sum < 300000) }' "$DIR/actual.err" || fail "growth-promoted counts are not deltas"
grep -q '^jo-mem promoted kind=store count=300000 fun=memsrv.handle ' "$DIR/actual.err" || fail "exit row is not cumulative"
[ "$(grep -c '^jo-mem hint growth: ' "$DIR/actual.err")" = 3 ] || fail "expected a growth hint per report"

echo "  - Running server.jo without JO_MEMSTATS"
"$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ ! -s "$DIR/actual.err" ] || fail "growth reports without JO_MEMSTATS"

echo "  - Building server.jo without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/server.jo" -o "$DIR/server.run" || exit 1
JO_MEMSTATS=1 "$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(grep -c '^jo-mem growth runtime-region=' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth reports without names"
! grep -q '^jo-mem growth-promoted' "$DIR/actual.err" || fail "growth-promoted rows without --mem-profile"
! grep -q '^jo-mem hint growth' "$DIR/actual.err" || fail "growth hint without --mem-profile"
# one rebuild hint at the first growth report, and the one of the exit report
awk '/^jo-mem summary/ { s = 1 } /^jo-mem hint rebuild/ { if (s) after++; else before++ } END { exit !(before == 1 && after == 1) }' "$DIR/actual.err" || fail "expected one rebuild hint in the growth reports"

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
