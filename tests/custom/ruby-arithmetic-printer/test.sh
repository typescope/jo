#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../../.." && pwd)"
cd "$PROJECT_ROOT"

scala-cli run --server=false project.scala \
  stack-lang/ruby/Trees.scala stack-lang/ruby/Printer.scala \
  "$TEST_DIR/Check.scala" --main-class RubyArithmeticPrinterCheck
