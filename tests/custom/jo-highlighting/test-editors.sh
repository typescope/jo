#!/usr/bin/env bash
set -euo pipefail

# Requires Vim and the existing docs dependencies (npm ci --prefix docs).
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export JO_HIGHLIGHT_FIXTURES="$WORK/fixtures.json"
export JO_HIGHLIGHT_ERRORS="$WORK/vim-errors.txt"
node "$TEST_DIR/editor-fixtures.cjs" "$JO_HIGHLIGHT_FIXTURES"
node "$TEST_DIR/textmate.mjs" "$JO_HIGHLIGHT_FIXTURES"

if ! vim -Nu NONE -n -es -S "$TEST_DIR/vim.vim"; then
  cat "$JO_HIGHLIGHT_ERRORS"
  exit 1
fi
echo "Vim highlighting passed"
