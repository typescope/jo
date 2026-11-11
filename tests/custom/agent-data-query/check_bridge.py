"""Exercise the Jo bridge of a data-query template.

Run from a prepared copy of the template, with the copy on PYTHONPATH. Every
program goes through run_jo, as the agent's programs do, and the database is
inspected directly to confirm what each program wrote.
"""

from pathlib import Path
import sqlite3

from jo_bridge import run_jo

HEAD = '''namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*
'''
PREFIX = HEAD + 'def analyzeDocuments(): Unit receives stdout, db =\n'
DATABASE = Path('out/database.db')


def rows():
    with sqlite3.connect(DATABASE) as connection:
        return connection.execute('SELECT id, title, draft FROM documents ORDER BY id').fetchall()


def run(body, user_id=1):
    return run_jo(PREFIX + '  ' + body + '\n', user_id)


def titles(user_id):
    return run('for doc in db.query(All) do println(doc.title)', user_id)


# Input guards.
guard = 'Jo source must contain 1 to 50,000 characters.'
assert run_jo('', 1) == guard
assert run_jo('x' * 50_001, 1) == guard

# Generated code cannot name the runtime or the FFI, cannot widen the
# capabilities of the entry point, and must supply the entry point.
for program in (
    PREFIX + '  DatabaseRuntime.platformMain()\n',
    PREFIX + '  println(py.module("sys").argv.size)\n',
    HEAD + 'def analyzeDocuments(): Unit receives stdout, db, IO.args =\n  println(IO.args.size)\n',
    HEAD + 'def other(): Unit receives stdout =\n  println("x")\n',
):
    result = run_jo(program, 1)
    assert result.startswith('Jo compilation failed:'), (program, result)

# Reads are scoped to the user the host selects.
user1 = titles(1)
assert 'Q4 Budget Report' in user1 and 'Technical Design' not in user1, user1
user2 = titles(2)
assert 'Technical Design' in user2 and 'Q4 Budget Report' not in user2, user2

# Invalid updates are rejected before anything is written.
before = rows()
for updates in (
    '[Title := "Should Not Persist", Draft := "false"]',
    '[FieldUpdate(Draft, StringValue("false"))]',
    '[Content := true]',
):
    result = run(f'println(db.updateById(1, {updates}))')
    assert result.startswith('Jo program failed:'), (updates, result)
    assert rows() == before, updates

result = run('println(db.update(All, [Title := "Should Not Persist", Draft := "false"]))')
assert result.startswith('Jo program failed:'), result
assert rows() == before

result = run('println(db.query(All, limit = -1).size)')
assert result.startswith('Jo program failed:') and 'non-negative' in result, result
assert rows() == before

# A valid update is committed and visible to later programs.
result = run('println(db.updateById(1, [Draft := false, Title := "Valid Title"]))')
assert result.endswith('true'), result
assert rows()[0] == (1, 'Valid Title', 0), rows()[0]
assert 'Valid Title' in titles(1)

# Writes are scoped to the user too: another user's document cannot be
# deleted by id, and deleting everything deletes only the user's own documents.
result = run('println(db.deleteById(11))')
assert result.endswith('false'), result
assert rows()[10] == before[10], rows()[10]

result = run('println(db.delete(All))')
assert result.endswith('10'), result
assert run('println(db.query(All).size)', 1).endswith('0')
assert run('println(db.query(All).size)', 2).endswith('8')
assert [row[0] for row in rows()] == [row[0] for row in before if row[0] > 10]

print('Generated code is confined to the selected user')
