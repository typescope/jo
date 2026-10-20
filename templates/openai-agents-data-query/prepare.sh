#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="$PROJECT_DIR/data"
OUT_DIR="$PROJECT_DIR/out"

mkdir -p "$OUT_DIR"
jo compile --sast "$OUT_DIR/api" "$DATA_DIR/DatabaseAPI.jo"
jo compile --sast "$OUT_DIR/runtime" --use-runtime-api python \
  "$DATA_DIR/os.jo" "$DATA_DIR/sqlite3.jo" "$DATA_DIR/Runtime.jo" \
  --lib "$OUT_DIR/api"
rm -f "$OUT_DIR/database.db"
python3 -c 'import sqlite3, sys; sqlite3.connect(sys.argv[1]).executescript(open(sys.argv[2]).read())' \
  "$OUT_DIR/database.db" "$DATA_DIR/seed.sql"

echo "Prepared Jo libraries and a fresh database in $OUT_DIR"
