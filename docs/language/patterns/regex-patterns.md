# Regex Patterns

Regex literals can be used as patterns in `is` expressions and `match` cases.

This page describes the design for regex-pattern matching and variable binding.
For regex literal syntax and supported regex subset, see
[Regular Expressions](../expressions/regular-expressions.md).

## Motivation

Jo is pattern-oriented. Regex patterns let you keep parsing and validation logic
inside the same pattern-matching model used by other Jo data patterns.

## Pattern Forms

Regex pattern support has two forms:

1. Plain regex pattern:

```jo
if date is #r"^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$" then
  ...
```

2. Bound match-result regex pattern:

```jo
match date
  case m#r"^(\d{4})-(\d{2})-(\d{2})$" =>
    ...
  case _ =>
    ...
```

`m` is bound as `MatchResult` when the regex match succeeds.

## Matching Semantics

Regex pattern matching uses **search semantics** (equivalent to
`String.matchFirst(...)`), aligned with JavaScript and Ruby style matching.

- A regex pattern succeeds iff a first match exists.
- A plain pattern `#r"...“` only tests success/failure.
- A bound pattern `m#r"...“` additionally binds `m: MatchResult`.

## Binding Semantics

### MatchResult Binder

In `m#r"...“`:

- `m` is bound only on successful match
- `m` is available in flow-typed scope (`if` then-branch / matching case body)

### Named Group Bindings

Named groups are also introduced as flow bindings.

Example:

```jo
if date is #r"^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$" then
  new Date(y.toInt, m.toInt, d.toInt)
else
  ...
```

Rules:

- Binding type is `String`.
- If a named group did not participate, its bound value is `""`.
- If code needs to distinguish “unmatched” vs “matched empty”, bind
  `MatchResult` and use `isGroupMatched("name")`.

## Spacing Rule for Binder Syntax

To avoid ambiguity, binder syntax requires no space:

- `m#r"...“` => regex binder form
- `m #r"...“` => not binder syntax

## Scope and Flow Typing

Regex-pattern bindings follow the same flow-typing rules as other patterns and
`is` expressions. In particular, bindings can flow through larger boolean/term
expressions (for example `&&` / `||`) and later pattern components according to
the existing flow-typing semantics.

This applies to both:

- match-result binder (`m`)
- named-group bindings (`y`, `m`, `d`, etc.)

Examples:

- `if s is m#r"...“ && m.length > 0 then ...`
- `if s is #r"(?<y>\d+)" && y.toInt > 0 then ...`

See:

- [Is Expression](../expressions/is-expression.md)
- [Pattern Matching Semantics](semantics.md)

## Examples

### `is` expression

```jo
if date is #r"^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$" then
  new Date(y.toInt, m.toInt, d.toInt)
else
  abort "Invalid date"
```

### `match` with bound result

```jo
match date
  case m#r"^(\d{4})-(\d{2})-(\d{2})$" =>
    new Date(m[1].toInt, m[2].toInt, m[3].toInt)
  case _ =>
    abort "Invalid date"
```

### Optional named group handling

```jo
if input is m#r"^(?<name>\w+)(?:-(?<tag>\w+))?$" then
  if m.isGroupMatched("tag") then
    println tag
  else
    println "no tag"
```

## See Also

- [Is Expression](../expressions/is-expression.md)
- [Regular Expressions](../expressions/regular-expressions.md)
- [Pattern Matching Semantics](semantics.md)
