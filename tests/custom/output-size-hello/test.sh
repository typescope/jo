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

SRC="$PROJECT_ROOT/tests/pos/hello.jo"

echo "  - Compiling with JavaScript backend"
"$PROJECT_ROOT/bin/jo" compile --js "$SRC" -o /tmp/test_hello.js
check_lines "js" /tmp/test_hello.js 180

echo "  - Compiling with Ruby backend"
"$PROJECT_ROOT/bin/jo" compile --ruby "$SRC" -o /tmp/test_hello.rb
check_lines "ruby" /tmp/test_hello.rb 210

echo "  - Compiling with Python backend"
"$PROJECT_ROOT/bin/jo" compile --python "$SRC" -o /tmp/test_hello.py
check_lines "python" /tmp/test_hello.py 150

if [ "$FAILED" -ne 0 ]; then
  echo "[error] $TEST_NAME: output size regression detected"
  exit 1
fi

echo "  ✓ All tests passed for $TEST_NAME"
