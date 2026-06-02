# Pattern Language

This document specifies the pattern language used in `match` expressions and pattern definitions.

## Syntax

```
pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern  = "if" expr
assign_pattern = "then" assignment {"," assignment}

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern
               | qualid
               | type_pattern
               | bind_pattern
               | apply_pattern
               | "(" pattern ")"
               | sequence_pattern

literal_pattern  = integer | boolean | char | string
type_pattern     = ident ":" type
bind_pattern     = ident "@" simple_pattern
apply_pattern    = qualid "(" [pattern {"," pattern}] ")"
sequence_pattern = "[" [sequence_items] "]"
```

`expr_pattern` follows the same operator-expression rules as term expressions: juxtaposed `simple_pattern`s form applications.

The or (`|`), and (`&`), and not (`!`) patterns are **not** special syntax — they are ordinary operator patterns defined in `Predef.jo`.
It is intentional that we do not define operator precedence for patterns, as no one can remember them.

## Pattern Categories

### [Pattern Forms](pattern-forms.md)
The basic building blocks: literals, variables, types, bindings, applications,
sequences, and composition with or (`|`), and (`&`), and not (`!`).

### [Regex Patterns](regex-patterns.md)
Regex literals in `is` and `match` patterns, with optional `Match` binding and
named-group flow bindings.

### [Sequence Patterns](sequence-patterns.md)
Atom and repeat patterns for matching variable-length lists.

### [Semantics](semantics.md)
Definite binding rules, flow typing, exhaustiveness, and evaluation order.

### [Pattern Definitions](../definitions/pattern-definitions.md)
Defining reusable, named patterns with parameters.

## Usage Contexts

| Context | Example |
|---|---|
| `match` expression | `match opt` / `case Some(x) => x` / `case None => 0` |
| `is` expression | `if x is Some(n) then n else 0` |
| Pattern value definition | `val Point(x, y) = getPoint()` |
| `for` loop | `for Some(x) in list do println(x)` |
| Pattern definition body | `pattern Positive: Int =` / `  case n if n > 0` |

## See Also

- [Algebraic Data Types](../definitions/union-definition.md) - Types designed for pattern matching
- [Union Types](../types/union-types.md) - Union types and exhaustiveness
- [Syntax Summary](../syntax-summary.md) - Complete grammar
