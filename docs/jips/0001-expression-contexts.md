---
author: Fengyun Liu
status: Draft
created: 2026-07-12
---

# JIP 0001 — Regularize expression syntax

## Summary

Jo splits expressions into two categories. **Closed** expressions may appear in
delimited positions — inside `(...)`, in call arguments, in an interpolation
`\{...}`. **Open** expressions are limited to block phrases and indented colon
arguments. Colon calls, dot chains, `match`, `rescue`, `allow`, and `with` are
open-only: they are allowed nowhere else.

This proposal intends to eliminate the two categories.  With this proposal,
almost every syntactic form becomes usable almost everywhere. One restriction
remains, and it is easy to state and justify:

> An *inline* colon call may not appear directly in a comma-context,
> because *commas do not nest*.

## Motivation

While the closed/open split may improve readability, it pays a high price for
regularity and usability. Nothing about the shape of a `match` explains why it
is rejected where an `if` is allowed:

| Form | Closed | Open |
|---|---|---|
| word sequence, lambda, `if` | ✅ | ✅ |
| `match`, colon call, dot chain, `rescue`, `allow`/`with` | — | ✅ |

The line is arbitrary and difficult to justify. A `match`, a dot chain, or an indented
colon call cannot be passed to a function or used as a condition. Each must first be lifted into
a `val` and named.

On reflection, the split actually addresses two different problems:

1. **Disambiguation** — keep a comma-separated argument list from colliding with a comma-separated colon call.
2. **Style** — discourage nestings that the parser accepts but a human finds hard to read.

Trying to addressing style problems in the syntax is dangerous:

- **Justification**: It is difficult to justify styles as they are in the end a problem of "style".
- **Complexity**: Addressing style problems in the syntax would unnecessarily complicate the grammar.

If we leave the style problem to programmers, and base the syntax design only on
uncontroversial cases about disambiguation, we may achieve simplicity and
regularity.

## What actually matters

What the syntax design really cares about is to disallow nesting inline colon
calls inside a comma-list, because **commas do not nest**.

Nesting an inline colon call is always confusing: `f(foo: 1, 2)` could mean
`f(foo(1, 2))` or `f(foo(1), 2)`. Therefore, it is not a style problem, it is
simply wrong.

In contrast, nesting an indented colon call or `match`-expression in
parenthesis-calls is only a style problem:

```jo
fact(
  foo:
    a
    b
  ,
  20
)

foo(
  match opt
  case Some x => x
  case None   => 0
)
```

The code above is clear to the reader, even though it can be better written as

```jo
fact:
  foo: a b
  20

foo:
  match opt
  case Some x => x
  case None   => 0

```

or

```jo
fact: foo(a, b), 20

foo:
  match opt
    case Some x => x
    case None   => 0
```

If we leave style problems to programmers but still guard against nested inline
colon syntax in a comma-context, simplicity and regularity can be restored.

## Specification

The following syntax design achieves the design goal:

```ebnf
simple_expr = words
            | lambda
            | if_expr                       (* with or without else *)
            | match_expr
            | indented_colon_call
            | dot_chain                     (* multi-line. inline-colon tail is the sole caveat *)
            | allow_expr | with_expr
            | rescue_expr

expr = simple_expr | inline_colon_call

inline_colon_call = atom NS ":" comma_arg {"," comma_arg}   (* commas stay on the ":" line *)

comma_arg = [name "="] simple_expr    (* call args, list/bracket items, index, inline colon args *)
```

Every comma-separated position takes `simple_expr` — call arguments, list and index items,
inline colon arguments, and the right-hand side of a `with`/`allow` binding (bounded by `in`).
That single choice keeps the inline colon call out of all of them. A block is terminated by a
dedent rather than a comma, so it also takes the phrase-only statements (`assign`, `while`,
`for`, `val`/`var`, `def`, …).

With the design above, a comma-delimited expression may only be `simple_expr`, so
one can never nest inline colon calls directly in these positions.

## Consequences

**Colon calls as arguments.** An indented colon call is a valid argument: it is delimited by
indentation and bounded by the bracket. The inline form is not:

```jo
f(g:
    a
    b)
f(foo: 1, 2)     // rejected — write `f foo(1, 2)`
```

**Nested colon calls.** The rule that rejects an inline nesting allows an indented one:

```jo
foo: a, bar(c, d)   // inline argument → parentheses required
foo: a, bar:        // indented argument → nesting is fine
  c
  d
```

**Trailing lambdas and blocks.** These need no special case. A lambda body, a `match`, or an
indented colon call is delimited by `=>`, `case`, or indentation — never a comma — so it is an
ordinary argument that lands last because it ends its line:

```jo
list.fold: 0, (acc, x) =>
  acc + x
```

**Conditions.** Any expression can now be a condition — `if xs.filter(p).any then …`, or a
`match` used directly as one. An else-less `if` is also allowed in a comma-list: `f(if c then a,
b)`, but it will be rejected by the typer.

## Prior art: Ruby

Ruby is the only language that has a comma-separated call syntax without
parentheses, its *command call*.

Ruby allows a bare command only as a call's **first** argument, where it greedily consumes the
rest and so becomes the only argument. Anywhere else is a syntax error:

- `p(foo 2, 3)` and `p foo 2, 3` parse as `p(foo(2, 3))` — `foo` is first, so it takes the rest
- `p(1, foo 2)` and `p 1, foo 2` are rejected — `foo` is not first, even though it is last

Jo resolves the same ambiguity by making the grouping explicit instead of relying on a greedy
rule:

```jo
p: foo 2 3        // valid
p: 1, foo 2 3     // valid
p: foo: 2, 3      // rejected — commas do not nest
```

## Alternatives considered

**Widen both tiers until they overlap.** Keep closed and open, then add exceptions until the two
sets coincide for common code. This trades one list of restrictions for a longer list of
exceptions, which is both complex and difficult to justify.
