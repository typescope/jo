# Words, Terms, Phrases and Blocks

This document specifies the syntax of Jo's computational constructs. Jo uses a word-based syntax organized into four syntactic levels: words, terms, phrases, and blocks.

## Overview

Jo distinguishes between **syntactic structure** and **semantic categories**:

**Syntactic levels:**

- **[word](words.md)** - atomic syntactic unit
- **[term](isolated-terms.md)** - sequence of words
- **[phrase](phrases.md)** - element of a block
- **[block](blocks.md)** - sequence of phrases

**Semantic categories:**

- **expression** - produces a value
- **statement** - does not produce a value

These are orthogonal: a phrase may be either an expression or a statement.

## Word-Based Syntax

Jo's syntax is word-based: terms are sequences of words. This design enables uniform parenthesis-less syntax across terms, types, and patterns:

```jo
// Terms
add 1 2
List.map f list

// Types
List Int
Option String

// Patterns
Some x
Cons head tail
```

## Quick Reference

### [Words](words.md)
Atomic syntactic units including literals, identifiers, lambdas, function calls, and more.

### [Regular Expressions](regular-expressions.md)
Regex literals and the `jo.regex` API for search, capture, replace, and split.

### [Terms](isolated-terms.md)
Sequences of words with optional modifiers that have natural boundaries in delimited contexts. See also [Block Terms](block-terms.md) for terms that appear in blocks and end based on indentation.

### [Phrases](phrases.md)
Elements that appear in blocks: expressions, assignments, definitions, and control flow.

### [Control Flow](control-flow.md)
`if`, `match`, `while`, `for`, `break`, and `continue`.

### [Blocks](blocks.md)
Sequences of phrases with indentation-based structure.

## Examples

```jo
// Word: literal
42

// Word: lambda
x => x + 1

// Word: function application
println("hello")

// Term: sequence of words
add 1 2

// Term with modifiers
value as Int with config = newConfig

// Phrase: assignment
count = count + 1

// Phrase: control flow
if x > 0 then
  println("positive")
else
  println("non-positive")

// Block: sequence of phrases
val x = 10
val y = 20
x + y
```

## See Also

- [Syntax Summary](../syntax-summary.md) - Complete grammar reference
- [Expression Parsing](../concepts/expression-syntax.md) - Parsing details
