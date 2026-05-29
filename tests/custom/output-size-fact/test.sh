#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"

echo "Testing $TEST_NAME"

FAILED=0

check_lines() {
  local backend="$1"
  local file="$2"
  local limit="$3"
  local actual
  actual=$(wc -l < "$file")
  if [ "$actual" -gt "$limit" ]; then
    echo "  [error] $backend output has $actual lines (limit: $limit)"
    FAILED=1
  else
    echo "  ✓ $backend: $actual lines (<= $limit)"
  fi
}

SRC="$PROJECT_ROOT/tests/pos/fact.jo"

echo "  - Compiling with JavaScript backend"
"$PROJECT_ROOT/bin/jo" compile --js "$SRC" -o /tmp/test_fact.js
check_lines "js" /tmp/test_fact.js 185

echo "  - Compiling with Ruby backend"
"$PROJECT_ROOT/bin/jo" compile --ruby "$SRC" -o /tmp/test_fact.rb
check_lines "ruby" /tmp/test_fact.rb 220

echo "  - Compiling with Python backend"
"$PROJECT_ROOT/bin/jo" compile --python "$SRC" -o /tmp/test_fact.py
check_lines "python" /tmp/test_fact.py 160

if [ "$FAILED" -ne 0 ]; then
  echo "[error] $TEST_NAME: output size regression detected"
  exit 1
fi

echo "  ✓ All tests passed for $TEST_NAME"
