const fs = require('node:fs');
const fixtures = [];

function add(name, source, checks) {
  fixtures.push({ name, source, checks });
}

for (const count of [2, 3, 4, 7]) {
  const slashes = '/'.repeat(count);
  const shorter = '/'.repeat(count - 1);
  const longer = '/'.repeat(count + 1);
  add(`comment delimiter ${count}`,
    `${slashes}[\n${shorter}]\ninside\n${longer}]\nstill inside\n${slashes}]\ndef after = 42`, [
      { line: 3, text: 'inside', kind: 'comment' },
      { line: 5, text: 'inside', kind: 'comment' },
      { line: 7, text: 'after', kind: 'code' }
    ]);
}

add('same-count comments do not nest', '//[\n//[ text\n//]\ndef after = 42', [
  { line: 2, text: 'text', kind: 'comment' },
  { line: 4, text: 'after', kind: 'code' }
]);

for (const count of [3, 4, 5, 8]) {
  const quotes = '"'.repeat(count);
  const shorter = '"'.repeat(count - 1);
  const longer = '"'.repeat(count + 1);
  add(`string delimiter ${count}`,
    `val text = ${quotes}\n${shorter}\ninside\n${longer}\nstill inside\n\\${quotes}\ndef after = 42`, [
      { line: 3, text: 'inside', kind: 'string' },
      { line: 5, text: 'inside', kind: 'string' },
      { line: 7, text: 'after', kind: 'code' }
    ]);
}

add('quotes and comment markers in raw strings',
  'val text = """\n  "quoted" text // text\n  //[ reader\'s note\n"""\ndef after = 42', [
    { line: 2, text: 'quoted', kind: 'string' },
    { line: 3, text: 'note', kind: 'string' },
    { line: 5, text: 'after', kind: 'code' }
  ]);

add('comment markers in ordinary strings',
  'val text = "//["\n//[ A reader\'s `note`\ninside\n//]\ndef after = 42', [
    { line: 1, text: '//[', kind: 'string' },
    { line: 2, text: 'note', kind: 'comment' },
    { line: 3, text: 'inside', kind: 'comment' },
    { line: 5, text: 'after', kind: 'code' }
  ]);

add('block markers inside line comments', '// text //[\ndef after = 42', [
  { line: 1, text: 'text', kind: 'comment' },
  { line: 2, text: 'after', kind: 'code' }
]);

add('stray apostrophes do not open strings', "reader's\ndef after = 42", [
  { line: 1, text: 'reader', kind: 'code' },
  { line: 2, text: 'after', kind: 'code' }
]);

for (const literal of [String.raw`"\\"`, String.raw`"a \"quoted\" string"`,
  String.raw`"\u{1F600}"`, '""']) {
  add(`ordinary string ${literal}`, `val text = ${literal}\ndef after = 42`, [
    { line: 1, text: literal, offset: 1, kind: 'string' },
    { line: 2, text: 'after', kind: 'code' }
  ]);
}

add('Unicode escape in a raw string', 'val text = """\n' + String.raw`\u{1F600}` + '\n"""\ndef after = 42', [
  { line: 2, text: String.raw`\u{1F600}`, kind: 'string' },
  { line: 4, text: 'after', kind: 'code' }
]);

for (const literal of ["'a'", "'😀'", `'"'`, String.raw`'\b'`, String.raw`'\f'`,
  String.raw`'\n'`, String.raw`'\r'`, String.raw`'\t'`, String.raw`'\''`, String.raw`'\\'`]) {
  add(`character ${literal}`, `val value = ${literal}\ndef after = 42`, [
    { line: 1, text: literal, offset: 1, kind: 'string' },
    { line: 2, text: 'after', kind: 'code' }
  ]);
}

for (const literal of [String.raw`'\u{41}'`, String.raw`'\q'`]) {
  add(`invalid character escape ${literal}`, `val value = ${literal}\ndef after = 42`, [
    { line: 1, text: literal, kind: 'code' },
    { line: 2, text: 'after', kind: 'code' }
  ]);
}

fs.writeFileSync(process.argv[2], JSON.stringify(fixtures));
