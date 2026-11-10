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

rm -rf "$DIR/actual.out" "$DIR"/*.run

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

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
