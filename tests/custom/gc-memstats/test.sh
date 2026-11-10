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

# Under the default policy, `main` opens a region, so `remember` and `note`
# store into containers of that region: no store rows, and few objects in the
# runtime region. The region of `accumulate` holds its 5000 nodes at once, and
# `Iterator.fold` builds a long list in the region of a library function.
echo "  - Building with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/app.jo" -o "$DIR/app.run" || exit 1
JO_MEMSTATS=1 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
grep -q '^jo-mem summary peak-rss-kb=[0-9]* allocated=' "$DIR/actual.err" || fail "no summary"
grep -q '^jo-mem loops collect=[0-9]* marked=[0-9]* freed=[0-9]*$' "$DIR/actual.err" || fail "no loops line"
awk '/^jo-mem region / && /fun=memapp.accumulate .* caller=memapp.main$/ { split($3, a, "="); if (a[2] >= 4960) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no region row for accumulate"
grep -q '^jo-mem region objects=[0-9]* fun=jo\.Iterator\$fold at=.* caller=memapp\.main$' "$DIR/actual.err" || fail "no region row for fold"
awk '/^jo-mem to-runtime / { split($3, a, "="); if (a[1] == "direct" && a[2] < 100) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "direct allocations in the runtime region"
! grep -q '^jo-mem promoted kind=store' "$DIR/actual.err" || fail "store rows for containers of main"
! grep -q '^jo-mem hint store: ' "$DIR/actual.err" || fail "store hint without store rows"
grep -q '^jo-mem hint library: ' "$DIR/actual.err" || fail "no library hint"
! grep -q '^jo-mem hint runtime: ' "$DIR/actual.err" || fail "runtime hint when main opens a region"
grep -q '^jo-mem hint region: ' "$DIR/actual.err" || fail "no region hint"
! grep -q '^jo-mem hint traced: ' "$DIR/actual.err" || fail "traced hint without repeated tracing"
! grep -q '^jo-mem growth' "$DIR/actual.err" || fail "growth report for a bounded runtime region"

echo "  - Running without JO_MEMSTATS"
"$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
[ ! -s "$DIR/actual.err" ] || fail "statistics printed without JO_MEMSTATS"
JO_MEMSTATS=0 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ ! -s "$DIR/actual.err" ] || fail "statistics printed with JO_MEMSTATS=0"
JO_MEMSTATS= "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ ! -s "$DIR/actual.err" ] || fail "statistics printed with an empty JO_MEMSTATS"
JO_MEMSTATS=yes "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
grep -q '^jo-mem summary ' "$DIR/actual.err" || fail "no statistics with JO_MEMSTATS=yes"

# Under `none`, `main` runs in the runtime region, function 0: `remember`
# promotes 5000 nodes by a store into the array of `main`, and `main`
# allocates the rest directly there. `List.append` opens no region under
# `none`, so it has no row.
echo "  - Building with --region-policy none and --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile --region-policy none --region memapp.remember --region memapp.accumulate "$DIR/app.jo" -o "$DIR/app.run" || exit 1
JO_MEMSTATS=1 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
grep -q '^jo-mem promoted kind=store count=5000 fun=memapp.remember at=.*app.jo:[0-9]* into=jo.Array caller=runtime$' "$DIR/actual.err" || fail "no store row for remember"
awk '/^jo-mem region / && /fun=memapp.accumulate .* caller=runtime$/ { split($3, a, "="); if (a[2] >= 4960) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no region row for accumulate"
! grep -q 'List\$append' "$DIR/actual.err" || fail "row for append without a region"
awk '/^jo-mem to-runtime / { split($3, a, "="); if (a[1] == "direct" && a[2] >= 3000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no direct allocations in main"
grep -q '^jo-mem hint store: ' "$DIR/actual.err" || fail "no store hint"
grep -q '^jo-mem hint runtime: ' "$DIR/actual.err" || fail "no runtime hint"
! grep -q '^jo-mem hint main' "$DIR/actual.err" || fail "main hint"

echo "  - Building without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region-policy none "$DIR/app.jo" -o "$DIR/app.run" || exit 1
JO_MEMSTATS=1 "$DIR/app.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$DIR/expect.check" || fail "unexpected output"
grep -q '^jo-mem summary ' "$DIR/actual.err" || fail "no summary"
grep -q '^jo-mem hint rebuild with --mem-profile' "$DIR/actual.err" || fail "no hint to rebuild"
grep -q '^jo-mem hint runtime: ' "$DIR/actual.err" || fail "no runtime hint without names"
! grep -q '^jo-mem promoted' "$DIR/actual.err" || fail "rows without --mem-profile"

# Under the default policy, the loop of `main` collects the nodes that
# `handle` stores into its state and overwrites, about 300000 nodes of 32
# bytes: no growth report, and the loop collections free them.
echo "  - Building server.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/server.jo" -o "$DIR/server.run" || exit 1
JO_MEMSTATS=1 "$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "299999" ] || fail "unexpected output of server.jo"
! grep -q '^jo-mem growth' "$DIR/actual.err" || fail "growth report while the loop collects"
awk '/^jo-mem loops / { split($5, a, "="); if (a[2] > 250000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "the loop of main freed too little"

# Growth reports: under `none`, `main` runs in the runtime region, and
# `handle` promotes a node per call by a store into the state of `main`,
# 300000 in all. The runtime region reaches 1024, 2048 and 4096 segments of 62
# objects, about 63000, 127000 and 254000 objects, so there are 3 reports.
# The next one would need 8192 segments.
echo "  - Building server.jo with --region-policy none and --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile --region-policy none --region memsrv.handle "$DIR/server.jo" -o "$DIR/server.run" || exit 1
JO_MEMSTATS=1 "$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "299999" ] || fail "unexpected output of server.jo"
[ "$(grep -c '^jo-mem growth runtime-region=[0-9]* peak-rss-kb=[0-9]* direct=[0-9]* store=[0-9]* trace=[0-9]* return=[0-9]*$' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth reports"
awk '/^jo-mem summary/ { s = NR } /^jo-mem growth/ { g = NR } END { exit !(g > 0 && g < s) }' "$DIR/actual.err" || fail "growth reports after the summary"
[ "$(grep -c '^jo-mem growth-promoted kind=store count=[0-9]* fun=memsrv.handle at=.*server.jo:[0-9]* into=memsrv.State caller=runtime$' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth-promoted rows for handle"
[ "$(grep -c '^jo-mem growth-promoted' "$DIR/actual.err")" = 3 ] || fail "unexpected growth-promoted rows"
! grep -q '^jo-mem growth-region' "$DIR/actual.err" || fail "growth-region report for growth of the runtime region"
awk '/^jo-mem growth-promoted / { split($4, a, "="); n++; sum += a[2]; if (a[2] >= 150000) bad = 1 } END { exit !(n == 3 && !bad && sum < 300000) }' "$DIR/actual.err" || fail "growth-promoted counts are not deltas"
grep -q '^jo-mem promoted kind=store count=300000 fun=memsrv.handle ' "$DIR/actual.err" || fail "exit row is not cumulative"
[ "$(grep -c '^jo-mem hint growth: ' "$DIR/actual.err")" = 1 ] || fail "expected one growth hint"
awk '/^jo-mem growth / { n++ } /^jo-mem hint growth: / { if (n != 1) bad = 1 } END { exit bad }' "$DIR/actual.err" || fail "growth hint not at the first report"
awk '/^jo-mem summary/ { exit } /^jo-mem hint runtime: / { found = 1 } END { exit found }' "$DIR/actual.err" || fail "runtime hint in the growth reports of server.jo"

echo "  - Running server.jo without JO_MEMSTATS"
"$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ ! -s "$DIR/actual.err" ] || fail "growth reports without JO_MEMSTATS"

echo "  - Building server.jo with --region-policy none and without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region-policy none --region memsrv.handle "$DIR/server.jo" -o "$DIR/server.run" || exit 1
JO_MEMSTATS=1 "$DIR/server.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(grep -c '^jo-mem growth runtime-region=' "$DIR/actual.err")" = 3 ] || fail "expected 3 growth reports without names"
! grep -q '^jo-mem growth-promoted' "$DIR/actual.err" || fail "growth-promoted rows without --mem-profile"
! grep -q '^jo-mem hint growth' "$DIR/actual.err" || fail "growth hint without --mem-profile"
# one rebuild hint at the first growth report, and the one of the exit report
awk '/^jo-mem summary/ { s = 1 } /^jo-mem hint rebuild/ { if (s) after++; else before++ } END { exit !(before == 1 && after == 1) }' "$DIR/actual.err" || fail "expected one rebuild hint in the growth reports"

# Growth from code in the runtime region: under `none`, the loop of `main`
# allocates 1040000 objects directly in the runtime region, with no row to
# show. `tick`, named by `--region`, prints the growth reports when it
# returns, with the runtime hint once, before the summary.
echo "  - Building direct.jo with --region-policy none and --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile --region-policy none --region memdirect.tick "$DIR/direct.jo" -o "$DIR/direct.run" || exit 1
JO_MEMSTATS=1 "$DIR/direct.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "519999" ] || fail "unexpected output of direct.jo"
awk '/^jo-mem growth / { split($5, a, "="); if (a[2] >= 1000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "no growth report with direct allocations"
awk '/^jo-mem summary/ { exit } /^jo-mem hint runtime: / { n++ } END { exit !(n == 1) }' "$DIR/actual.err" || fail "expected one runtime hint in the growth reports"
! grep -q '^jo-mem growth-promoted' "$DIR/actual.err" || fail "growth-promoted rows for direct allocations"
! grep -q '^jo-mem hint growth' "$DIR/actual.err" || fail "growth hint without rows"

# Growth of a region other than the runtime region: under the default policy,
# `main` keeps its list in its own region. The region reaches 1024, 2048,
# 4096, 8192 and 16384 segments, so there are 5 reports, and none for the
# runtime region.
echo "  - Building direct.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/direct.jo" -o "$DIR/direct.run" || exit 1
JO_MEMSTATS=1 "$DIR/direct.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "519999" ] || fail "unexpected output of direct.jo"
[ "$(grep -c '^jo-mem growth-region objects=[0-9]* peak-rss-kb=[0-9]* fun=memdirect.main at=.*direct.jo:[0-9]* caller=runtime$' "$DIR/actual.err")" = 5 ] || fail "expected 5 growth-region reports for main"
[ "$(grep -c '^jo-mem growth' "$DIR/actual.err")" = 5 ] || fail "unexpected growth reports for direct.jo"
awk '/^jo-mem growth-region / { split($3, a, "="); if (a[2] < 63000 || a[2] <= last) bad = 1; last = a[2] } END { exit bad }' "$DIR/actual.err" || fail "growth-region counts do not grow"
awk '/^jo-mem summary/ { exit } /^jo-mem hint region: / { n++ } END { exit !(n == 1) }' "$DIR/actual.err" || fail "expected one region hint in the growth reports"
awk '/^jo-mem summary/ { s = NR } /^jo-mem growth-region/ { g = NR } END { exit !(g > 0 && g < s) }' "$DIR/actual.err" || fail "growth-region reports after the summary"

echo "  - Building direct.jo without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/direct.jo" -o "$DIR/direct.run" || exit 1
JO_MEMSTATS=1 "$DIR/direct.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(grep -c '^jo-mem growth-region objects=[0-9]* peak-rss-kb=[0-9]*$' "$DIR/actual.err")" = 5 ] || fail "expected 5 growth-region reports without names"
awk '/^jo-mem summary/ { s = 1 } /^jo-mem hint rebuild/ { if (s) after++; else before++ } END { exit !(before == 1 && after == 1) }' "$DIR/actual.err" || fail "expected one rebuild hint in the growth-region reports"
"$DIR/direct.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ ! -s "$DIR/actual.err" ] || fail "growth-region reports without JO_MEMSTATS"

# A collecting loop that calls no region function prints the growth reports
# itself when it collects: only `main` opens a region. A report that is due
# waits for the next collection, so several thresholds can share one line.
echo "  - Building direct.jo with --region-policy none --region memdirect.main"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile --region-policy none --region memdirect.main "$DIR/direct.jo" -o "$DIR/direct.run" || exit 1
JO_MEMSTATS=1 "$DIR/direct.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "519999" ] || fail "unexpected output of direct.jo"
awk '/^jo-mem summary/ { exit } /^jo-mem growth-region objects=[0-9]* peak-rss-kb=[0-9]* fun=memdirect.main / { n++ } END { exit !(n >= 1) }' "$DIR/actual.err" || fail "no growth-region report from the loop"

# Results returned to the runtime region: under `none`, `main` runs there,
# and `wrap` returns 60000 objects to it. The row names `wrap`, and its
# caller is `runtime`.
echo "  - Building ret.jo with --region-policy none and --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile --region-policy none --region memret.wrap "$DIR/ret.jo" -o "$DIR/ret.run" || exit 1
JO_MEMSTATS=1 "$DIR/ret.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "1919970000" ] || fail "unexpected output of ret.jo"
grep -q '^jo-mem to-runtime direct=[0-9]* store=0 trace=0 return=60000$' "$DIR/actual.err" || fail "no return count"
grep -q '^jo-mem promoted kind=return count=60000 fun=memret\.wrap at=.*ret.jo:[0-9]* caller=runtime$' "$DIR/actual.err" || fail "no return row for wrap"
[ "$(awk '/^jo-mem summary/ { s = 1 } s && /^jo-mem hint runtime: / { n++ } END { print n }' "$DIR/actual.err")" = 1 ] || fail "expected one runtime hint for returned results"

# Under the default policy, the results go to the region of `main`, whose
# loop frees them.
echo "  - Building ret.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/ret.jo" -o "$DIR/ret.run" || exit 1
JO_MEMSTATS=1 "$DIR/ret.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "1919970000" ] || fail "unexpected output of ret.jo"
grep -q '^jo-mem to-runtime direct=[0-9]* store=0 trace=0 return=0$' "$DIR/actual.err" || fail "results returned to the runtime region"
! grep -q '^jo-mem promoted kind=return' "$DIR/actual.err" || fail "return row under the default policy"
awk '/^jo-mem loops / { split($5, a, "="); if (a[2] >= 40000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "the loop of main did not free the results"

# A server loop in a region function: `serve` reads each reply only in the
# iteration that gets it, so its loop frees them.
echo "  - Building serve.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/serve.jo" -o "$DIR/serve.run" || exit 1
JO_MEMSTATS=1 "$DIR/serve.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "80000000" ] || fail "unexpected output of serve.jo"
! grep -q '^jo-mem growth' "$DIR/actual.err" || fail "growth report for serve.jo"
grep -q '^jo-mem loops collect=[1-9][0-9]* ' "$DIR/actual.err" || fail "no loop collection in serve"

# Repeated tracing: every level of `build` traces the whole result again, so
# `traced` in the summary far exceeds `allocated`, the traced row names
# `build`, and the traced hint fires, also without names.
echo "  - Building quad.jo with --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --mem-profile "$DIR/quad.jo" -o "$DIR/quad.run" || exit 1
JO_MEMSTATS=1 "$DIR/quad.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
[ "$(cat "$DIR/actual.out")" = "3000" ] || fail "unexpected output of quad.jo"
awk '/^jo-mem (summary|closes) / { for (i = 3; i <= NF; i++) { split($i, a, "="); v[a[1]] = a[2] } } END { exit !(v["traced"] > 20 * v["allocated"]) }' "$DIR/actual.err" || fail "traced is not far above allocated"
grep -q '^jo-mem traced objects=[0-9]* fun=memquad.build at=.*quad.jo:[0-9]* caller=memquad.main$' "$DIR/actual.err" || fail "no traced row for build"
[ "$(grep -c '^jo-mem hint traced: ' "$DIR/actual.err")" = 1 ] || fail "expected one traced hint"

echo "  - Building quad.jo without --mem-profile"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/quad.jo" -o "$DIR/quad.run" || exit 1
JO_MEMSTATS=1 "$DIR/quad.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
grep -q '^jo-mem hint traced: ' "$DIR/actual.err" || fail "no traced hint without names"
! grep -q '^jo-mem traced' "$DIR/actual.err" || fail "traced row without names"

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
