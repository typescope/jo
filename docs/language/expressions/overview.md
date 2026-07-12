# Words, Expressions, Phrases, and Blocks

> *Programs should be written for people to read, and only incidentally for machines to execute.*
> — Abelson & Sussman, SICP

Jo's expression language is organized around four syntactic levels, with the aim to improve readability while maintaining simplicity.

## The Four Levels

**Word** — the smallest self-contained expression unit. A word requires no external delimiters to determine where it ends; boundaries follow from the token structure alone. Literals, identifiers, `(expr)`, `new C()`, `[...]` list literals, and suffixed forms `a.b`, `a()`, `a[i]` are all words.

**Expression** — a sequence of one or more words, extended with a few special forms: lambdas, `if`, `match`, colon calls, dot chains, `rescue`, and `allow`/`with`.

**Phrase** — an element of a block. Every expression is a valid phrase. Phrases also include constructs only valid at block level: assignments, local definitions, loops, and loop control.

**Block** — a vertically aligned sequence of phrases. A block is introduced by a definition's `=`, by `=>`, or by a keyword (`then`, `else`, `do`, `in`, `case =>`), and continues while phrases remain more indented than the introducer.

## One restriction: commas do not nest

An **inline colon call** separates its arguments with commas on a single line:

```jo
send: to, subject
```

Dropped directly into another comma-separated list, those commas merge with the list's own — `f(foo: 1, 2)` could mean `f(foo(1, 2))` or `f(foo(1), 2)`, and nothing on the page decides. So an inline colon call may not appear directly inside a comma-separated list: a call's arguments, a list literal, an index, or another inline colon call's arguments. Wrap it in parentheses, or use the indented colon form, whose arguments are separated by newlines and carry no comma:

```jo
// Not valid: the inline colon call's commas collide with the call's
foo(bar: 1, 2)

// Valid: parenthesize
foo((bar: 1, 2))

// Valid: indented colon form, bounded by indentation
foo(bar:
  1
  2)
```

Every other form — including the indented colon call and the dot chain — is bounded by indentation or by its own keywords, never by a comma, so it composes freely everywhere.

## Indentation

Jo uses indentation structurally. A block continues while its phrases are more indented than the introducing keyword or operator, and ends when indentation returns to that level.

All phrases within a block must start at the same column (vertical alignment).
