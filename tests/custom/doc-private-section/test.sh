#!/bin/bash

# Regression test: members of a `private section` must stay out of the
# generated documentation.
#
# The nav tree already excluded private sections, but the search index did not:
# JsonEmitter.emitSearch recursed into a section's members whenever the section
# had visible members, without first checking whether the section itself was
# private. Every member of `private section MapTree`, `SetTree`, `ListImpl` and
# friends was therefore searchable in the published stdlib docs.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
TEST_NAME="$(basename "$DIR")"
API_FILE="tests/custom/doc-private-section/api.jo"
DOC_DIR="${TMPDIR:-/tmp}/jo-doc-private-section-$$"

cleanup() { rm -rf "$DOC_DIR"; }

finish() {
    local status=$?
    if [ "$status" -eq 0 ]; then
        cleanup
    else
        echo "  failed; documentation output left in $DOC_DIR"
    fi
}

trap finish EXIT

echo "Testing $TEST_NAME"

cd "$PROJECT_ROOT"

"$PROJECT_ROOT/bin/jo" compile --doc --out "$DOC_DIR" "$API_FILE" > /dev/null

DATA="$DOC_DIR/data.js"

# Public members are published.
for want in \
    '"fullName": "docprivate.PublicHelpers"' \
    '"fullName": "docprivate.PublicHelpers.twice"'
do
    if ! grep -q -- "$want" "$DATA"; then
        echo "Public symbol missing from documentation: $want"
        exit 1
    fi
done

# Nothing from the private section is published, in nav or in search.
for unwanted in \
    "InternalHelpers" \
    "thrice" \
    "Hidden" \
    "HiddenAlias"
do
    if grep -q -- "$unwanted" "$DATA"; then
        echo "Private section member leaked into documentation: $unwanted"
        grep -o "\"fullName\": \"[^\"]*$unwanted[^\"]*\"" "$DATA" | sort -u | head
        exit 1
    fi
done

echo "  ✓ All tests passed for $TEST_NAME"
