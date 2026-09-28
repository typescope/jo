#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../../.." && pwd)"
cd "$PROJECT_ROOT"

scala-cli run --server=false project.scala \
  stack-lang/python/Trees.scala stack-lang/python/Printer.scala \
  stack-lang/js/Trees.scala stack-lang/js/Printer.scala \
  "$TEST_DIR/Check.scala" --main-class UnaryPrinterCheck
