import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHighlighter } from '../../../docs/node_modules/shiki/dist/index.mjs';

const grammar = JSON.parse(readFileSync(new URL('../../../tools/vscode/syntaxes/jo.tmLanguage.json', import.meta.url)));
const fixtures = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const highlighter = await createHighlighter({
  themes: ['github-light'],
  langs: [{ ...grammar, name: 'jo' }]
});

try {
  for (const fixture of fixtures) {
    const lines = fixture.source.split('\n');
    const { tokens } = highlighter.codeToTokens(fixture.source, {
      lang: 'jo', theme: 'github-light', includeExplanation: true
    });
    for (const check of fixture.checks) {
      const start = lines[check.line - 1].indexOf(check.text);
      assert.ok(start >= 0, fixture.name);
      const column = start + (check.offset ?? 0);
      let end = 0;
      const token = tokens[check.line - 1].find(token => (end += token.content.length) > column);
      const scopes = token.explanation.flatMap(item => item.scopes.map(scope => scope.scopeName));
      const actual = scopes.some(scope => scope.startsWith('comment.')) ? 'comment' :
        scopes.some(scope => scope.startsWith('string.') || scope.startsWith('constant.character.')) ? 'string' : 'code';
      assert.equal(actual, check.kind, `${fixture.name}:${check.line}: ${check.text}`);
    }
  }
} finally {
  highlighter.dispose();
}

console.log(`Shiki/VS Code highlighting passed (${fixtures.length} cases)`);
