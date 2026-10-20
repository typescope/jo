#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$PROJECT_DIR/out"
rm -f "$PROJECT_DIR/out/database.db"
python3 -c 'import sqlite3, sys; sqlite3.connect(sys.argv[1]).executescript(open(sys.argv[2]).read())' \
  "$PROJECT_DIR/out/database.db" "$PROJECT_DIR/data/seed.sql"
cp "$PROJECT_DIR/data/DatabaseAPI.jo" "$PROJECT_DIR/data/Runtime.jo" \
  "$PROJECT_DIR/data/os.jo" "$PROJECT_DIR/data/sqlite3.jo" "$PROJECT_DIR/sandbox/"

cd "$PROJECT_DIR"
jo build --spec sandbox/jo.toml guest
