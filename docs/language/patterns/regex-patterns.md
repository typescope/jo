# Regex Patterns

Regex literals can be used as patterns in `is` expressions and `match` cases.

This page describes regex-pattern matching and variable binding.
For regex literal syntax and supported regex subset, see
[Regular Expressions](../expressions/regular-expressions.md).

## Pattern Forms

Regex pattern support has two forms:

1. Plain regex pattern:

```jo
if date is `^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$` then
  ...
```

2. Bound match-result regex pattern (using `@`):

```jo
match date
case m @ `^(\d{4})-(\d{2})-(\d{2})$` =>
  ...
case _ =>
  ...
```

`m` is bound as `Match` when the regex match succeeds.

The two forms can be combined: a bound-result pattern can also contain named
groups, giving access to both `m` and the individual group bindings:

```jo
if input is m @ `^(?<name>\w+)(?:-(?<tag>\w+))?$` then
  println name                    // named group binding
  println m.isGroupMatched("tag") // Match for optional-group check
```

## Matching Semantics

Regex pattern matching uses **search semantics** (equivalent to
`String.matchFirst(...)`), aligned with JavaScript and Ruby style matching.

- The scrutinee must have type `String`.
- A regex pattern succeeds iff a first match exists.
- A plain pattern `` `...` `` only tests success/failure.
- A bound pattern `` m @ `...` `` additionally binds `m: Match`.

## Binding Semantics

### Match Binder

In `` m @ `...` ``:

- `m` is bound only on successful match
- `m` is available in flow-typed scope (`if` then-branch / matching case body)

### Named Group Bindings

Named groups are introduced as bindings in both `is` expressions and `match` cases.

`is` expression:

```jo
if date is `^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$` then
  new Date(y.toInt, m.toInt, d.toInt)
else
  ...
```

`match` case:

```jo
match date
case `^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$` =>
    new Date(y.toInt, m.toInt, d.toInt)
case _ =>
    abort "Invalid date"
```

Rules:

- Binding type is `String`.
- If a named group did not participate, its bound value is `""`.
- If code needs to distinguish "unmatched" vs "matched empty", bind
  `Match` and use `isGroupMatched("name")`.
- Name collisions follow the same flow-typing binding rules as other pattern
  bindings.

## Typing Elaboration

Regex patterns are type-checked by elaborating into ordinary pattern guards and
assignments.

Let:

- `_scrut` = synthesized temporary for the scrutinee value
- `_m` = synthesized temporary for intermediate `Match`
- `g1..gn` = named groups from the regex literal metadata

Synthesized temporaries (`_scrut`, `_m`) are compiler-generated and are **not**
added to user-visible flow scope.

### 1. Plain regex pattern: `` `...` ``

#### a) No named groups

```text
_scrut if `...`.matchFirst(_scrut) is Some(_)
```

#### b) Has named groups

```text
(_scrut if `...`.matchFirst(_scrut) is Some(_m))
&
(g1 = _m.getOrEmpty("g1"), g2 = _m.getOrEmpty("g2"), ...)
```

### 2. Bound regex pattern: `` m @ `...` ``

#### a) No named groups

```text
_scrut if `...`.matchFirst(_scrut) is Some(m)
```

#### b) Has named groups

```text
(_scrut if `...`.matchFirst(_scrut) is Some(m))
&
(g1 = m.getOrEmpty("g1"), g2 = m.getOrEmpty("g2"), ...)
```

`getOrEmpty(name)` is a method on `Match` that returns the captured group
text when the group participated, or `""` when it did not.

## Scope and Flow Typing

Regex-pattern bindings follow the same flow-typing rules as other patterns and
`is` expressions. Bindings introduced by a pattern are available in the
continuation of `&&` expressions and in the matching case body.

This applies to both:

- match-result binder (`m`)
- named-group bindings (`y`, `m`, `d`, etc.)

Examples:

- `` s is m @ `...` && m.length > 0 `` — `m.length` is code-point length of the whole match
- `` s is `(?<y>\d+)` && y.toInt > 0 ``

See:

- [Is Expression](../expressions/is-expression.md)
- [Pattern Matching Semantics](semantics.md)

## See Also

- [Is Expression](../expressions/is-expression.md)
- [Regular Expressions](../expressions/regular-expressions.md) — Literal syntax and supported subset
- [Regular Expression Guide](../../guides/regular-expression.md) — Common tasks and worked examples
- [Pattern Matching Semantics](semantics.md)
