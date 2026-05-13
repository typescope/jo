# Words, Expressions, Phrases, and Blocks

Jo's expression language is organized around four syntactic levels. Understanding their relationship explains most of Jo's indentation-based syntax.

## The Four Levels

**Word** — the smallest self-contained expression unit. A word requires no external delimiters to determine where it ends; boundaries follow from the token structure alone. Literals, identifiers, `(expr)`, `new C()`, `[...]` list literals, and suffixed forms `a.b`, `a()`, `a[i]` are all words.

**Expression** — a sequence of one or more words, extended with a few special forms. Expressions come in two varieties:

- A **closed expression** is bounded by surrounding syntax — parentheses, commas, keywords. It never contains a top-level comma, `=`, or unadorned `:`.
- An **open expression** extends to an indentation boundary. Colon calls, dot chains, and open `if`/`match`/`allow`/`with` are open expressions.

**Phrase** — an element of a block. Every open expression is a valid phrase. Phrases also include constructs only valid at block level: assignments, local definitions, loops, and loop control.

**Block** — a vertically aligned sequence of phrases. A block is introduced by an operator (`=`, `=>`) or keyword (`then`, `do`, `case =>`) and continues while phrases remain more indented than the introducing token.

## Word-Based Syntax

Jo uses juxtaposition for function application across expressions, types, and patterns:

```jo
// Expressions: word sequences are function calls
add 1 2
List.map f list

// Types: same rule applies
List Int
Option String

// Patterns: same rule applies
Some x
Cons head tail
```

There is no special production for "function call" — it is simply word sequencing. The same rule governs all three syntactic domains.

## Why Closed vs Open Expressions?

The split solves a parsing problem.

Inside delimiters — a call's argument list `f(...)`, a fence `(...)`, a string interpolation `\{...}` — the parser must stop at commas and closing brackets. An open expression's continuation rules would conflict there. So closed expressions are used in those positions.

At phrase level — inside blocks, or as arguments of an indented colon call — expressions can extend over multiple lines guided by indentation. So open expressions are used there.

This is why colon calls and open `if`/`match` cannot appear directly as function arguments. To use them in a delimited position, extract to a `val` binding:

```jo
// Not valid: colon call is an open expression
foo(bar: 1, 2)

// Valid: extract to a phrase, then pass the result
val result = bar: 1, 2
foo(result)
```

## Indentation

Jo uses indentation structurally. A block continues while its phrases are more indented than the introducing keyword or operator, and ends when indentation returns to that level.

All phrases within a block must start at the same column (vertical alignment).

## Chapter Map

| Page | Covers |
|---|---|
| [Expression Forms](expression-forms.md) | Atoms, words, closed and open expressions |
| [Applications](applications.md) | All call syntax: `f()`, `f: arg`, dot chains, type and bracket application |
| [Phrases](phrases.md) | Assignment, definitions, and other block-only constructs |
| [Blocks](blocks.md) | Block delimiters, alignment, and block values |
| [Control Flow](control-flow.md) | `if`, `match`, `while`, `for`, `return`, `break`, `continue` |
| [Lambdas](lambdas.md) | Lambda syntax, closures, and SAM interface adaptation |
| [Literals](literals.md) | Integer, float, boolean, character, string, and list literals |
| [Is Expression](is-expression.md) | Boolean pattern matching and flow typing |
| [Regular Expressions](regular-expressions.md) | Regex literals and the `jo.regex` API |
| [Syntax Summary](../syntax-summary.md) | Complete formal grammar |
