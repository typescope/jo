#!/usr/bin/env bash
set -euo pipefail

# Requires the existing docs dependencies (npm ci --prefix docs).
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export JO_HIGHLIGHT_FIXTURES="$WORK/fixtures.json"
node "$TEST_DIR/editor-fixtures.cjs" "$JO_HIGHLIGHT_FIXTURES"
node "$TEST_DIR/textmate.mjs" "$JO_HIGHLIGHT_FIXTURES"
