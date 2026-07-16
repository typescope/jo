#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

cd "$DIR"
rm -rf .build jo.lock out.txt err.txt

set +e
"$JO" build --spec jo.toml guest >out.txt 2>err.txt
code=$?
set -e

if [ "$code" -eq 0 ]; then
  echo "[error] jo build guest should fail on compile errors"
  echo "-- stdout --"
  cat out.txt
  echo "-- stderr --"
  cat err.txt
  exit 1
fi

grep -q "Deferred function Guest.missing has no default implementation and is not linked" err.txt || {
  echo "[error] missing compile diagnostic on stderr"
  echo "-- stdout --"
  cat out.txt
  echo "-- stderr --"
  cat err.txt
  exit 1
}

rm -rf .build jo.lock out.txt err.txt
