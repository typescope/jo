# Words, Expressions, Phrases, and Blocks

> *Programs should be written for people to read, and only incidentally for machines to execute.*
> — Abelson & Sussman, SICP

Jo's expression language is organized around four syntactic levels, with the aim to improve readability while maintaining simplicity.

## The Four Levels

**Word** — the smallest self-contained expression unit. A word requires no external delimiters to determine where it ends; boundaries follow from the token structure alone. Literals, identifiers, `(expr)`, `new C()`, `[...]` list literals, and suffixed forms `a.b`, `a()`, `a[i]` are all words.

**Expression** — a sequence of one or more words, extended with a few special forms: lambdas, `if`, `match`, colon calls, dot chains, `rescue`, and `allow`/`with`.

**Phrase** — an element of a block. Every expression is a valid phrase. Phrases also include constructs only valid at block level: assignments, local definitions, loops, and loop control.

**Block** — a vertically aligned sequence of phrases. A block is introduced by a definition's `=`, by `=>`, or by a keyword (`then`, `else`, `do`, `in`, `case =>`), and continues while phrases remain more indented than the introducer.

## Indentation

Jo uses indentation structurally. A block continues while its phrases are more indented than the introducing keyword or operator, and ends when indentation returns to that level.

All phrases within a block must start at the same column (vertical alignment).

## Dropping a non-Unit value

An expression produces a value, It is a common programming mistake to silently
drop a value. The compiler warns if a non-Unit value is silently dropped:

```jo
fact(10)   // warning: value of type Int is silently dropped
```

To acknowledge an intentional discard, use:

```jo
val _ = fact(10)   // no warning
```

A function may also specify that its result may be silently dropped, which is
common for the [builder pattern](https://en.wikipedia.org/wiki/Builder_pattern):

```jo
class AgentBuilder
  @discardableResult
  def model(m: String): AgentBuilder = ...

  @discardableResult
  def maxTokens(n: Int): AgentBuilder = ...

  def build: Agent = ...
end

// statement style
builder.maxTokens(200)    // OK, marked as @discardableResult
builder.model("gpt-5.6")
val agent = builder.build

// chaining style
val agent= builder
  .maxTokens(200)
  .model("gpt-5.6")
  .build
```

::: info The exceptions for silently dropping a value

The rule allows the following exceptions:

- The value is of the type `Unit` or `Bottom`
- The value is produced by a call where the function is marked `@discardableResult`
- The value has a member `callDynamic` or `selectDynamic`

:::
