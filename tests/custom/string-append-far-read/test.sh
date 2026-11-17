#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

# Under 100 MB: a copy of the string at each read, or every 32 reads,
# would need several hundred MB
run_app() {
    (ulimit -v 100000; "$1"; echo "exit=$?") > "$DIR/actual.out" 2>&1
}

echo "  - Building with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" "$DIR/app.jo" -o "$DIR/app.run" || exit 1
run_app "$DIR/app.run"
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] LLVM test failed for $TEST_NAME"
    exit 1
}

echo "  - Building with the 32-bit register backend"
"$PROJECT_ROOT/bin/jo" compile --reg "$DIR/app.jo" -o "$DIR/app.run" || exit 1
run_app "$DIR/app.run"
diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] register backend test failed for $TEST_NAME"
    exit 1
}

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME"
