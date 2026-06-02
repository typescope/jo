# Words, Expressions, Phrases, and Blocks

> *Programs should be written for people to read, and only incidentally for machines to execute.*
> — Abelson & Sussman, SICP

Jo's expression language is organized around four syntactic levels, with the aim to improve readability while maintaining simplicity.

## The Four Levels

**Word** — the smallest self-contained expression unit. A word requires no external delimiters to determine where it ends; boundaries follow from the token structure alone. Literals, identifiers, `(expr)`, `new C()`, `[...]` list literals, and suffixed forms `a.b`, `a()`, `a[i]` are all words.

**Expression** — a sequence of one or more words, extended with a few special forms. Expressions come in two varieties:

- A **closed expression** is bounded by surrounding syntax — parentheses, commas, keywords.
- An **open expression** extends to an indentation boundary. Colon calls, dot chains, and `match`/`allow`/`with` are open-only forms.

**Phrase** — an element of a block. Every expression is a valid phrase. Phrases also include constructs only valid at block level: assignments, local definitions, loops, and loop control.

**Block** — a vertically aligned sequence of phrases. A block is introduced by an operator (`=`, `=>`) or keyword (`then`, `do`, `case =>`) and continues while phrases remain more indented than the introducing token.

## Closed vs Open Expressions

The closed/open distinction is a direct expression of this principle. Code is read at two levels of attention: local and structural. Jo assigns different expression forms to each level.

**Closed expressions** appear in delimited positions: inside `(...)`, call argument lists, and string interpolations `\{...}`. These are *local* contexts — a small piece of logic embedded within a larger expression. A reader scanning this level expects conciseness, so closed expressions are kept to simple forms: word sequences, lambdas, and if-then-else.

**Open expressions** appear as phrases in blocks and as arguments in indented colon calls. These are *structural* contexts where the expression is the primary content on the screen. A reader here is following the logic of the program, so open expressions include the full range of complex forms: colon calls, dot chains, `match`, `allow`, and `with`.

When you need a complex form in a delimited position, extract it to a `val` binding:

```jo
// Not valid: colon call is an open expression
foo(bar: 1, 2)

// Valid: name it, then pass it
val result = bar: 1, 2
foo(result)
```

### Forms at a glance

| | Closed | Open |
|---|---|---|
| **Where** | `(...)`, call args, `\{...}` | Block phrases, indented colon args |
| Word sequence | ✅ | ✅ |
| Lambda | ✅ | ✅ |
| If | ✅ | ✅ |
| Match | — | ✅ |
| Colon call | — | ✅ |
| Dot chain | — | ✅ |
| Allow / with | — | ✅ |

## Indentation

Jo uses indentation structurally. A block continues while its phrases are more indented than the introducing keyword or operator, and ends when indentation returns to that level.

All phrases within a block must start at the same column (vertical alignment).
