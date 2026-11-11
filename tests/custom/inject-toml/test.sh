#!/bin/bash
# Capability injection declared in jo.toml: --provide and
# --internal travel through compile-options, with a TOML literal string
# carrying a Jo string literal without escapes. A type-check-only invocation
# must accept those options and stop before linking.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
cd "$DIR"
rm -rf .build out.txt err.txt

fail() { echo "[error] $TEST_NAME: $1"; echo "-- stdout --"; cat out.txt 2>/dev/null; echo "-- stderr --"; cat err.txt 2>/dev/null; rm -rf .build out.txt err.txt jo.lock; exit 1; }

"$JO" check >out.txt 2>err.txt || fail "jo check failed"
[[ -n $(find .build/app -name '*.sast' -print -quit) ]] || fail "jo check did not write SAST"
[[ -z $(find .build/app -name '*.py' -print -quit) ]] || fail "jo check generated Python output"

"$JO" run >out.txt 2>err.txt || fail "jo run failed"
diff out.txt expect.check || fail "jo run output differs from expect.check"

CHECK_SPEC=$(mktemp .check-only.XXXXXX.toml) || fail "could not create check-only project spec"
trap 'rm -f -- "$CHECK_SPEC" "${CHECK_SPEC%.toml}.lock"' EXIT
sed '/^compile-options = \[$/a\  "--check-only", "--fatal-warnings",' jo.toml >"$CHECK_SPEC" || fail "could not write check-only project spec"
"$JO" check --spec "$CHECK_SPEC" >out.txt 2>err.txt || fail "jo check failed with an explicit --check-only option"
for command in build run; do
  if "$JO" "$command" --spec "$CHECK_SPEC" >out.txt 2>err.txt; then
    fail "jo $command accepted --check-only in compile-options"
  fi
  grep -Fq -- 'remove --check-only from compile-options' err.txt || fail "jo $command gave the wrong diagnostic"
done

rm -rf .build out.txt err.txt jo.lock
echo "  ✓ All tests passed for $TEST_NAME"
