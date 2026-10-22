#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$PROJECT_ROOT"

"$JO" compile --ruby --use-runtime-api ruby "$DIR/app.jo" -o "$WORK/app.rb"
ruby "$WORK/app.rb" > "$WORK/actual.out" 2>&1
diff "$WORK/actual.out" "$DIR/expect.check"
echo "Ruby rounding representation passed"
