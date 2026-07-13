# Blocks

A block is a non-empty sequence of phrases:

```
block ::= phrase {phrase}
```

Use `pass` as a no-op placeholder when an empty block is needed:

```jo
if condition then
  pass
```

## Block Value

A block's value is its final phrase. If the final phrase is a statement rather than an
expression, a `Unit` value is synthesized.

## Block-Starting Constructs

The following tokens introduce a block after themselves:

**Control flow:** `then`, `else`, `do` (in `while`/`for`), `case =>`, `in` (in `allow`/`with`)

**Definitions:** `=` in `val`/`var`/`def`/`param`

**Lambdas:** `=>` (lambda body)

::: info
An **assignment** (`x = ...`) and a **`return`** take a single expression, not a block. Only a *definition's* `=` opens a block, so a bare `=` after a plain name never begins a multi-phrase region.

Pattern definitions (`pattern ... =`) do not start blocks either. Their right-hand side consists of case clauses, which are not expressions.
:::

## Block Delimiters

The **offside rule** applies: a block continues while phrases are more indented than the
introducing token, and ends when indentation returns to or before that level. All
phrases within a block must be vertically aligned at the same column.

```jo
val result =
  val x = 10   // part of block
  val y = 20   // part of block
  x + y        // part of block — block value
val other = 5  // outside block
```

## Block Scope

Variables defined in a block are scoped to that block and shadow any outer bindings of
the same name.

## See Also

- [Phrases](phrases.md) — Elements of blocks
- [Expression Forms](expression-forms.md) — Expression forms and where they appear
- [Syntax Summary](../syntax/syntax-summary.md) — Complete grammar
