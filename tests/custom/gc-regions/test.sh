#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

# Without reclamation the program needs about 2 GB. With `step` as a region
# function it runs in a few MB, so it must pass under a 200 MB limit.
echo "  - Building with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region gcapp.step "$DIR/app.jo" -o "$DIR/app.run"
(ulimit -v 200000; "$DIR/app.run") > "$DIR/actual.out" 2>&1
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] LLVM test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
