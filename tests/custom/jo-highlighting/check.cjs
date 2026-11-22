const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../..');
const context = vm.createContext({});

for (const file of ['highlight.min.js', 'jo.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, 'assets/doc', file), 'utf8'), context);
}

function highlight(source) {
  const result = context.hljs.highlight(source, { language: 'jo' });
  assert.equal(result.errorRaised, undefined);
  return result.value;
}

function escapeHtml(source) {
  return source.replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#x27;'
  })[char]);
}

function assertToken(source, scope) {
  const code = '\ndef answer = 42';
  const expectedCode = '\n<span class="hljs-keyword">def</span> answer ' +
    '<span class="hljs-operator">=</span> <span class="hljs-number">42</span>';
  assert.equal(highlight(source + code),
    `<span class="hljs-${scope}">${escapeHtml(source)}</span>${expectedCode}`);
}

// Documentation comments must remain one comment across lines and apostrophes.
assertToken("//[ A reader's note:\n  ! This is still documentation.\n//]", 'comment');
assertToken("// An author's note with \"quotes\" and //[", 'comment');

// Regex contents cannot start comments or strings, or swallow following code.
for (const literal of ['`//[a]`', '`(?is)//[a]`', '`"//[a]"`', '`a\\`//[a]`', '`\\\\`']) {
  assertToken(literal, 'regexp');
}
assertToken('`//[a]', 'regexp'); // Recover at the newline while editing a regex.

// Comment delimiters must have the same slash count, without matching a suffix.
for (const count of [2, 3, 4, 7]) {
  const slashes = '/'.repeat(count);
  const shorter = '/'.repeat(Math.max(1, count - 1));
  const longer = '/'.repeat(count + 1);
  assertToken(`${slashes}[\n${shorter}]\n${longer}]\nstill a comment\n${slashes}]`, 'comment');
}

// An apostrophe alone must not open a string, even in incomplete source.
assert.equal(highlight("reader's"), 'reader&#x27;s');
assert.equal(highlight("'unfinished\nnext line"), '&#x27;unfinished\nnext line');
assert.equal(highlight("'not a character'"), '&#x27;not a character&#x27;');

// Jo characters are complete, single-code-point literals, including escapes.
for (const literal of ["'a'", "'😀'", `'"'`, String.raw`'\b'`, String.raw`'\f'`,
  String.raw`'\n'`, String.raw`'\r'`, String.raw`'\t'`, String.raw`'\''`, String.raw`'\\'`]) {
  assertToken(literal, 'string');
}

// Unicode escapes are supported in strings, but not in character literals.
assert.ok(!highlight(String.raw`'\u{1F600}'`).includes('hljs-string'));
assertToken(String.raw`"\u{1F600}"`, 'string');
assertToken('"""\n  ' + String.raw`\u{1F600}` + '\n"""', 'string');

assertToken(String.raw`"a \"quoted\" string with // inside"`, 'string');

// Raw multiline strings may contain quotes, apostrophes, and comment markers.
assertToken('"""\n  "quoted" text\n  a reader\'s //[ note\n  \\\n"""', 'string');

// Extended delimiters must match exactly; shorter/longer quote runs are content.
for (const count of [3, 4, 5, 8]) {
  const quotes = '"'.repeat(count);
  const shorter = '"'.repeat(count - 1);
  const longer = '"'.repeat(count + 1);
  assertToken(`${quotes}\n${shorter}\n${longer}\nstill a string\n${quotes}`, 'string');
}

console.log('Jo syntax highlighting tests passed');
