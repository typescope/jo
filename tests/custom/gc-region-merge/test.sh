#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

rm -rf "$DIR/actual.out" "$DIR"/*.run

# Without reclamation the program needs about 1.4 GB. Under
# `--region-policy all` it runs in about 10 MB, so its peak RSS must stay
# under 64 MB. The gap is wide enough for the bound to hold on any machine.
echo "  - Building with LLVM"
"$PROJECT_ROOT/bin/jo" compile --llvm --clang "${CLANG:-clang}" --region-policy all "$DIR/app.jo" -o "$DIR/app.run"

RSS_KB=$(python3 - "$DIR/app.run" "$DIR/actual.out" <<'EOF'
import resource, subprocess, sys
with open(sys.argv[2], "w") as out:
    subprocess.run([sys.argv[1]], stdout=out, stderr=subprocess.STDOUT)
print(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss)
EOF
)

diff "$DIR/actual.out" "$DIR/expect.check" || {
    echo "[error] LLVM test failed for $TEST_NAME"
    cat "$DIR/actual.out"
    exit 1
}

if [ "$RSS_KB" -ge 65536 ]; then
    echo "[error] $TEST_NAME: peak RSS ${RSS_KB} KB, expected under 65536 KB"
    exit 1
fi

rm -rf "$DIR/actual.out" "$DIR"/*.run

echo "  ✓ All tests passed for $TEST_NAME (peak RSS ${RSS_KB} KB)"
