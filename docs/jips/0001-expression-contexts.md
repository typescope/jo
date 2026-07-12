---
author: Fengyun Liu
status: Draft
created: 2026-07-12
---

# JIP 0001 — Regularize expression syntax

## Summary

Jo's grammar splits expressions into two tiers. **Closed** expressions appear in delimited
positions — `(...)`, call arguments, interpolations `\{...}`. **Open** expressions appear as
block phrases and indented colon arguments. Colon calls, dot chains, `match`, `rescue`,
`allow`, and `with` are *open-only*.

This proposal replaces the two tiers with one principle:

> A form may appear in a context unless its own top-level delimiter is the token that
> terminates the context.

With the current proposal, only one restriction survives: an *inline* colon
call may not appear directly inside a comma-separated list — because *commas do
not nest*.

## Motivation

The closed/open split is a memory tax. Nothing about the shape of `match` explains why it is
barred where `if` is allowed:

| Form | Closed | Open |
|---|---|---|
| word sequence, lambda, `if` | ✅ | ✅ |
| `match`, colon call, dot chain, `rescue`, `allow`/`with` | — | ✅ |

The same friction blocks a `match`, a dot chain, or an indented colon call from appearing as
an argument or a condition. Each must be lifted into a `val` and named before use.

The split does two unrelated jobs, and only one is worth keeping:

1. **Disambiguation** — stop a comma-separated argument list from colliding with a
   comma-separated colon call.
2. **Style** — discourage nestings that are legible to the parser but hard for a human.

This proposal keeps job 1 as a one-line rule and drops the rest.

## What actually collides

The distinction that matters is which token *ends* an expression, and whether a form reuses
that token as its own separator. The colon call is the main concern, because only it can
interfere with a comma-list.


There are two forms of colon calls:

- **inline** — arguments separated by **commas**: `foo: a, b`
- **indented** — arguments separated by **newlines**:

  ```jo
  foo:
    a
    b
  ```

Drop each into a comma-list. The inline form leads to confusion — `f(foo: 1, 2)`
could mean `f(foo(1, 2))` or `f(foo(1), 2)`. The indented form is separated by
indentation, so the list's commas stay unambiguously its own.

The inline colon call is also the **only comma-list with no closing bracket**. A call ends in
`)`, a list in `]`, an `allow`/`with` binding list in `in` — but an inline colon call's right
edge is just the end of the line.

**Dot chains are not an exception.** An *inline select chain* (`xs.map(g).sum`) is an atom and
composes everywhere. A *dot chain* is the multi-line form — each continuation `.` begins its
own line — so its spine is newline-delimited and never collides.

## Specification

### The rule

> A form is admissible in a context unless its top-level delimiter is the token that
> terminates the context.

Contexts are classified by terminator. Most terminators — a closing bracket, a keyword —
match no form's delimiter, so most positions admit every form. Two are distinctive:

| Context | Terminator | Effect |
|---|---|---|
| **Comma-list** — `f(a, b)`, `[a, b]`, `arr[i, j]`, inline colon args, named args | comma | removes the inline colon call |
| **Block** — phrases, indented colon arguments | dedent | adds the phrase-only statements |

A comma-list excludes only the inline colon call, the sole form whose top-level delimiter is a
comma. A block admits every form plus statements (`val`, `def`, assignment, …). Every other
position — a fence, an interpolation, an `if`/`while`/`match`/`for` condition — admits every
form unchanged.

### Grammar

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
inline colon arguments, and the right-hand sides of `with`/`allow` bindings (bounded by `in`).
Only the inline colon call is thereby excluded. A block additionally admits the phrase-only
statements (`assign`, `while`, `for`, `val`/`var`, `def`, …).

Two properties follow:

- **Nesting.** An inline colon call's arguments are `simple_expr`, so one cannot nest directly
  in another — parentheses or the indented form are required. No separate rule is needed.

- **Commas stay on the `:` line.** A form that opens an indentation region ends its line, so
  no comma can follow it. Any indentation-opening argument is therefore automatically last,
  which is what makes trailing lambdas and blocks work with no "last argument" rule.

## Consequences

- **Colon calls as arguments.** An indented colon call is a legal argument
  (indentation-delimited, bracket-bounded). An inline one is not:

  ```jo
  f(g:
      a
      b)
  f(foo: 1, 2)     // rejected — write `f foo(1, 2)`
  ```

- **Nested colon calls.** An inline nesting needs parentheses. An indented one does not:

  ```jo
  foo: a, bar(c, d)   // inline argument → parentheses required
  foo: a, bar:        // indented argument → nesting is fine
    c
    d
  ```

- **Trailing lambdas and blocks** need no exception. A lambda body, `match`, or indented colon
  call is delimited by `=>`, `case`, or indentation, so it is an ordinary argument that lands
  last because it ends its line:

  ```jo
  list.fold: 0, (acc, x) =>
    acc + x
  ```

- **Conditions** admit any expression: `if xs.filter(p).any then …`, or a `match` as a
  condition.
- **Else-less `if` in a comma-list** is now legal (`f(if c then a, b)`) — the `then` block
  ends at the comma.

## Prior art: Ruby

Ruby is the one widely used language that ships a comma-separated call with
no brackets (its *command call*). It meets the same collision and splits it two ways:

- A bare command may **not** be a *non-final* argument: `p(1, foo 2, 3)` is a **syntax error**.
  This is exactly the case Jo rejects.
- A bare command **may** be the *final* argument, greedily consuming the rest: `p(foo 2, 3)`
  is `p(foo(2, 3))`. This is the *invisible associativity rule* of
  [Alternatives](#alternatives-considered) — the boundary is set by a right-greedy convention,
  not by the page.

Jo keeps the first move and declines the second.

## Alternatives considered

**Widen both tiers until they overlap.** Add targeted exceptions until the two sets coincide
for common code. This trades one list of restrictions for a longer list of exceptions, and
still presents two arbitrary tiers rather than one rule.

**Cancel the distinction entirely.** Allow every form everywhere and treat awkward nesting as
the programmer's problem. Its two targets — nested inline colon calls, and inline colon calls
in parenthesized calls — are exactly the comma collisions, which cannot be admitted without an
invisible associativity rule or reader-ambiguous grouping. Such code is not a problem of style,
it is simply wrong.
