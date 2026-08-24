#!/bin/bash

# Regression test: `annotation` declarations must be documented correctly --
# they must carry their doc comment, and be reported as kind "annotation"
# rather than as an ordinary function.
#
# Two separate defects used to drop them:
#   1. Scanner.isDefStartToken did not list Token.ANNOTATION, so the scanner
#      discarded the comments preceding an annotation declaration.
#   2. Namer.transformAnnotationDef never called index.setDocComment, so even a
#      doc that survived parsing was not recorded for the symbol.
# Both must stay fixed for the summary and body below to appear.
#
# A third defect published annotations with kind "function": they are lowered
# to a FunDef carrying Flags.Annotation, and the doc emitters did not look at
# that flag.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
TEST_NAME="$(basename "$DIR")"
API_FILE="tests/custom/doc-annotation/api.jo"
DOC_DIR="${TMPDIR:-/tmp}/jo-doc-annotation-$$"

cleanup() { rm -rf "$DOC_DIR" "$DOC_DIR-stdlib"; }

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

# The summary line is the first line of the doc comment, and the kind must be
# "annotation" -- not "function".
if ! grep -q -- '"fullName": "jo.docTestExperimental", "kind": "annotation", "summary": "Mark a definition as experimental"' "$DATA"; then
    echo "Wrong kind or missing summary for jo.docTestExperimental"
    grep -o '"fullName": "jo.docTestExperimental"[^}]*}' "$DATA" || echo "  (symbol not found at all)"
    exit 1
fi

# A single-line doc comment must work too.
if ! grep -q -- '"fullName": "jo.docTestDeprecated", "kind": "annotation", "summary": "Mark a definition as deprecated."' "$DATA"; then
    echo "Wrong kind or missing summary for jo.docTestDeprecated"
    grep -o '"fullName": "jo.docTestDeprecated"[^}]*}' "$DATA" || echo "  (symbol not found at all)"
    exit 1
fi

# The nav tree reports the kind as well.
if ! grep -q -- '"fullName": "jo.docTestExperimental", "kinds": \["annotation"\]' "$DATA"; then
    echo "Nav entry for jo.docTestExperimental is not kind annotation"
    exit 1
fi

# Entries in the symbols block carry the kind so the viewer can group them.
if ! grep -q -- '"kind": "annotation"' "$DATA"; then
    echo "symbols block does not tag annotations with their kind"
    exit 1
fi

# The `!` continuation lines must survive into the full doc body.
if ! grep -q -- 'The annotation carries a stability level so tooling can decide whether to' "$DATA"; then
    echo "Continuation lines of the annotation doc comment were dropped"
    exit 1
fi

# The stdlib's own annotations must carry their docs as well. Documenting a
# single source file does not pull in stdlib symbols, so document lib/ itself.
STDLIB_DOC_DIR="$DOC_DIR-stdlib"
"$PROJECT_ROOT/bin/jo" compile --doc --no-stdlib --out "$STDLIB_DOC_DIR" lib/*.jo lib/**/*.jo > /dev/null

STDLIB_DATA="$STDLIB_DOC_DIR/data.js"

if ! grep -q -- '"fullName": "jo.shadow", "kind": "annotation", "summary": "Mark an extension method' "$STDLIB_DATA"; then
    echo "Missing summary for the stdlib annotation jo.shadow"
    exit 1
fi

if ! grep -q -- '"fullName": "jo.compile.intrinsic", "kind": "annotation", "summary": "Mark an intrinsic definition"' "$STDLIB_DATA"; then
    echo "Missing summary for the stdlib annotation jo.compile.intrinsic"
    exit 1
fi

echo "  ✓ All tests passed for $TEST_NAME"
