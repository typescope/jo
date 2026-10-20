#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../../.." && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/jo-agent-update.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

for file in DatabaseAPI.jo Runtime.jo os.jo sqlite3.jo database.db; do
  cmp "$PROJECT_ROOT/templates/langchain-data-query/data/$file" \
    "$PROJECT_ROOT/templates/openai-agents-data-query/data/$file"
  cmp "$PROJECT_ROOT/templates/langchain-data-query/data/$file" \
    "$PROJECT_ROOT/templates/harpe-data-query/data/$file"
done
cmp "$PROJECT_ROOT/templates/langchain-data-query/jo_bridge.py" \
  "$PROJECT_ROOT/templates/openai-agents-data-query/jo_bridge.py"

HARPE_JO="$(command -v jo || true)"
export PATH="$PROJECT_ROOT/bin:$PATH"

for template in langchain-data-query openai-agents-data-query; do
  mkdir "$TMP_ROOT/$template"
  cp -a "$PROJECT_ROOT/templates/$template/." "$TMP_ROOT/$template/"

  (
    cd "$TMP_ROOT/$template"
    bash prepare.sh

    python3 - <<'PY'
from pathlib import Path
import sqlite3

from jo_bridge import run_jo

prefix = '''namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*
def analyzeDocuments(): Unit receives stdout, db =
'''

database = Path('out/database.db')

def rows():
    with sqlite3.connect(database) as connection:
        return connection.execute('SELECT id, title, draft FROM documents ORDER BY id').fetchall()

before = rows()

for updates in (
    '[Title := "Should Not Persist", Draft := "false"]',
    '[FieldUpdate(Draft, StringValue("false"))]',
    '[Content := true]',
):
    result = run_jo(prefix + f'  println(db.updateById(1, {updates}))\n', 1)
    assert result.startswith('Jo program failed:'), (updates, result)
    assert rows() == before, updates

result = run_jo(prefix + '  println(db.update(All, [Title := "Should Not Persist", Draft := "false"]))\n', 1)
assert result.startswith('Jo program failed:'), result
assert rows() == before

result = run_jo(prefix + '  println(db.updateById(1, [Draft := false, Title := "Valid Title"]))\n', 1)
assert result.endswith('true'), result

result = run_jo(prefix + '  for doc in db.query(All) do println(doc.title)\n', 1)
assert 'Valid Title' in result, result

result = run_jo(prefix + '  println(db.query(All, limit = -1).size)\n', 1)
assert result.startswith('Jo program failed:') and 'non-negative' in result, result

print('Invalid updates cannot write, and valid updates preserve readable documents')
PY
  )
done

mkdir "$TMP_ROOT/harpe-data-query"
cp -a "$PROJECT_ROOT/templates/harpe-data-query/." "$TMP_ROOT/harpe-data-query/"

(
  cd "$TMP_ROOT/harpe-data-query"
  if [[ -n "$HARPE_JO" && "$("$HARPE_JO" --version)" == 0.14* ]]; then
    PATH="$(dirname "$HARPE_JO"):$PATH" bash prepare.sh
    guest_file="$(find sandbox/.build/guest -path '*/target/guest.py' -print -quit)"
    test -n "$guest_file"
    database="$PWD/out/database.db"
  else
    # Compile its guest source directly against the same API and runtime.
    guest_file="$TMP_ROOT/harpe-guest.py"
    jo compile --python \
      --link jo.main=DatabaseRuntime.platformMain \
      --link DatabaseAPI.analyzeDocuments=UserTask.analyzeDocuments \
      --lib "$TMP_ROOT/langchain-data-query/out/api" \
      --link-lib "$TMP_ROOT/langchain-data-query/out/runtime" \
      sandbox/Task.jo -o "$guest_file"
    database="$PWD/data/database.db"
  fi

  HARPE_USER_ID=1 HARPE_DB_PATH="$database" \
    bash sandbox/run.sh "$guest_file" > "$TMP_ROOT/harpe-output"

  grep -q 'Q4 Budget Report' "$TMP_ROOT/harpe-output"
  if grep -q 'Technical Design' "$TMP_ROOT/harpe-output"; then
    echo 'Harpe guest returned another user’s document'
    exit 1
  fi
)
