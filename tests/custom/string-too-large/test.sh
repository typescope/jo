#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

# Run every case of app.jo and strip the source location of the abort
run_cases() {
    for n in 0 1 2 3 4; do
        (ulimit -v 400000; "$1" $n; echo "exit=$?") 2>&1
    done | sed -E 's/ \([^)]*\.jo:[0-9]+\)exit=/\nexit=/' > "$DIR/actual.out"
}

echo "  - Building with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/app.jo" -o "$DIR/app.run" || exit 1
run_cases "$DIR/app.run"
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] LLVM test failed for $TEST_NAME"
    exit 1
}

echo "  - Building with the 32-bit register backend"
"$PROJECT_ROOT/bin/jo" compile --reg "$DIR/app.jo" -o "$DIR/app.run" || exit 1
run_cases "$DIR/app.run"
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] register backend test failed for $TEST_NAME"
    exit 1
}

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
