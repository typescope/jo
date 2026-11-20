#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$PROJECT_ROOT"

"$JO" compile --python --use-runtime-api python "$DIR/app.jo" -o "$WORK/app.py"
python3 "$WORK/app.py" > "$WORK/actual.out" 2>&1
diff -u "$DIR/expect.check" "$WORK/actual.out"

if grep -Eq 'intToInteger|longToInteger|jo_py_Integer|^import operator( |$)' "$WORK/app.py"; then
  echo "[error] Integer operation or adapter was not intrinsified"
  exit 1
fi

if "$JO" compile --python --use-runtime-api python "$DIR/invalid-operands.jo" -o "$WORK/invalid.py" > "$WORK/invalid.out" 2>&1; then
  echo "[error] Expected non-integer operands to be rejected"
  exit 1
fi

diff -u "$DIR/invalid-operands.jo.check" "$WORK/invalid.out"
echo "Python Integer tests passed"
