#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../../.." && pwd)"
TMP_PROJECT="$(mktemp -d "${TMPDIR:-/tmp}/jo-agent-update.XXXXXX")"
trap 'rm -rf "$TMP_PROJECT"' EXIT

for file in DatabaseAPI.jo Runtime.jo; do
  cmp "$PROJECT_ROOT/templates/langchain-data-query/data/$file" \
    "$PROJECT_ROOT/templates/openai-agents-data-query/data/$file"
  cmp "$PROJECT_ROOT/templates/langchain-data-query/data/$file" \
    "$PROJECT_ROOT/templates/harpe-data-query/data/$file"
done

cp -a "$PROJECT_ROOT/templates/langchain-data-query/." "$TMP_PROJECT/"
cd "$TMP_PROJECT"
export PATH="$PROJECT_ROOT/bin:$PATH"
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

print('Invalid updates cannot write, and valid updates preserve readable documents')
PY
