#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

fail() {
    echo "[error] $TEST_NAME: $1"
    cat "$DIR/actual.out"
    exit 1
}

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run "$DIR"/*.ll

# Without reclamation the program needs about 2 GB. With `step` as a region
# function it runs in a few MB, so it must pass under a 200 MB limit.
echo "  - Building with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region-policy none --region gcapp.step "$DIR/app.jo" -o "$DIR/app.run"
(ulimit -v 200000; "$DIR/app.run") > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] LLVM test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

# Under the default policy, without `--region`, the loop of `main` and the
# region of `step` keep it under the same limit.
echo "  - Building with LLVM under the default policy"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/app.jo" -o "$DIR/app.run"
(ulimit -v 200000; "$DIR/app.run") > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "default policy"

# Only `main` opens a region, so only the collections of its loop free the
# garbage of `step`.
echo "  - Building with LLVM with a region for main only"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region-policy none --region gcapp.main "$DIR/app.jo" -o "$DIR/app.run"
(ulimit -v 200000; "$DIR/app.run") > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || fail "loop of main"

# One large array dropped per iteration: the loop collects at every
# iteration, so the program stays far below the 300 MB it would need.
echo "  - Building buffer.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/buffer.jo" -o "$DIR/buffer.run"
(ulimit -v 100000; "$DIR/buffer.run") > "$DIR/actual.out" 2>&1
[ "$(cat "$DIR/actual.out")" = "46050" ] || fail "buffer.jo"

# The entry function is ordinary: linked under another name and called by
# other code, and calling itself. Both store into the array of `IO.args`, in
# the runtime region, and run under `JO_LOOP_COLLECT=all` too.
echo "  - Building entry.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --link jo.main=memtest.run "$DIR/entry.jo" -o "$DIR/entry.run"
for mode in "" all; do
  JO_LOOP_COLLECT=$mode "$DIR/entry.run" start > "$DIR/actual.out" 2>&1
  diff "$DIR/actual.out" "$DIR/entry.check" || fail "entry.jo with JO_LOOP_COLLECT=$mode"
done

echo "  - Building self.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/self.jo" -o "$DIR/self.run"
for mode in "" all; do
  JO_LOOP_COLLECT=$mode "$DIR/self.run" 4 > "$DIR/actual.out" 2>&1
  diff "$DIR/actual.out" "$DIR/self.check" || fail "self.jo with JO_LOOP_COLLECT=$mode"
done

# Functions that return `Unit` keep their regions: `main` opens one. The
# closure that returns `Unit` through `Array.tabulate` is passed a level and
# clears it at entry.
GC_DIR="$PROJECT_ROOT/tests/gc"
echo "  - Building place-unit.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --keep-ll "$GC_DIR/place-unit.jo" -o "$DIR/unit.run" || exit 1
fun_calls() {
    awk -v f="$2" 'index($0, "define ") == 1 && index($0, f) { p = 1 } p && /^}/ { p = 0 } p' "$1"
}
fun_calls "$DIR/unit.run.ll" '@"gcplaceunit.main.' | grep -q 'Regions.enter' || fail "main opens no region"
fun_calls "$DIR/unit.run.ll" '@"gcplaceunit.units$lambda$apply.' | grep -q 'Regions.clearResult' || fail "the closure does not clear the level"
for mode in "" all; do
  JO_LOOP_COLLECT=$mode "$DIR/unit.run" > "$DIR/actual.out" 2>&1
  diff "$DIR/actual.out" "$GC_DIR/place-unit.jo.check" || fail "place-unit.jo with JO_LOOP_COLLECT=$mode"
done

# A level passed to a closure that does not take it must not reach a later
# `enter`, or a result is placed at a closed level and never freed. Every
# object outside the runtime region is freed.
echo "  - Building place-indirect.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$GC_DIR/place-indirect.jo" -o "$DIR/indirect.run" || exit 1
JO_MEMSTATS=1 "$DIR/indirect.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$GC_DIR/place-indirect.jo.check" || fail "place-indirect.jo"
awk '/^jo-mem summary / { for (i = 3; i <= NF; i++) { split($i, a, "="); v[a[1]] = a[2] } } END { exit !(v["allocated"] - v["freed"] == v["runtime-region"]) }' "$DIR/actual.err" || fail "objects of place-indirect.jo were not freed"

# The close fast path: when the merge budget refuses a merge, a region whose
# objects all escape is handed to its parent. In close-handoff.jo, these are
# the 500 regions of `pair`, the 500 of `putFirst`, and most of the 2500 of
# `step`, whose parent fills up. A region that keeps a tree is never handed
# over, since only its root escapes. In deep recursion, a region over the
# merge budget is collected, not handed over.
echo "  - Building close-handoff.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$GC_DIR/close-handoff.jo" -o "$DIR/handoff.run" || exit 1
JO_MEMSTATS=1 "$DIR/handoff.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$GC_DIR/close-handoff.jo.check" || fail "close-handoff.jo"
awk '/^jo-mem closes / { split($4, a, "="); if (a[1] == "handoff" && a[2] >= 3000) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "close-handoff.jo hands over too few regions"

echo "  - Building close-no-handoff.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$GC_DIR/close-no-handoff.jo" -o "$DIR/nohandoff.run" || exit 1
JO_MEMSTATS=1 "$DIR/nohandoff.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$GC_DIR/close-no-handoff.jo.check" || fail "close-no-handoff.jo"
grep -q '^jo-mem closes free=[0-9]* handoff=0 ' "$DIR/actual.err" || fail "close-no-handoff.jo hands over a region"

echo "  - Building close-deep.jo with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$GC_DIR/close-deep.jo" -o "$DIR/deep.run" || exit 1
JO_MEMSTATS=1 "$DIR/deep.run" > "$DIR/actual.out" 2> "$DIR/actual.err"
diff "$DIR/actual.out" "$GC_DIR/close-deep.jo.check" || fail "close-deep.jo"
awk '/^jo-mem closes / { split($6, a, "="); if (a[1] == "collect" && a[2] > 0) found = 1 } END { exit !found }' "$DIR/actual.err" || fail "close-deep.jo hands over regions over the merge budget"

rm -rf "$DIR/actual.out" "$DIR/actual.err" "$DIR"/*.run "$DIR"/*.ll

echo "  ✓ All tests passed for $TEST_NAME"
