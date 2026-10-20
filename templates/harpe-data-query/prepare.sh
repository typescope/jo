#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$PROJECT_DIR/out"
cp "$PROJECT_DIR/data/database.db" "$PROJECT_DIR/out/database.db"
cp "$PROJECT_DIR/data/DatabaseAPI.jo" "$PROJECT_DIR/data/Runtime.jo" \
  "$PROJECT_DIR/data/os.jo" "$PROJECT_DIR/data/sqlite3.jo" "$PROJECT_DIR/sandbox/"

cd "$PROJECT_DIR"
jo build --spec sandbox/jo.toml guest
