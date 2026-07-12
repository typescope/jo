# JIP 0001 — Replacing open/closed expressions with delimiter collision

**Author:** Fengyun Liu &nbsp;·&nbsp; **Status:** Draft &nbsp;·&nbsp; **Created:** 2026-07-12

## Summary

Jo's grammar currently divides expressions into two tiers. **Closed** expressions may appear
in delimited positions — inside `(...)`, call arguments, and string interpolations `\{...}`.
**Open** expressions may appear as block phrases and as indented colon-call arguments. Colon
calls, dot chains, `match`, `rescue`, `allow`, and `with` are *open-only*: they cannot appear
in a delimited position at all.

This proposal removes that two-tier scheme and replaces it with a single principle:

> A form may appear in a context unless its own top-level delimiter is the same token that
> terminates the context.

Under this rule the open/closed distinction disappears. Almost every form becomes usable
everywhere, matching what a programmer's syntactic intuition already expects. Exactly one
restriction survives — an *inline* colon call may not appear directly inside a
comma-separated list — and it survives for a reason a user can state in four words: *commas
do not nest*.

## Motivation

The closed/open distinction imposes a memory tax. To write Jo correctly a programmer must
recall which forms live in which tier:

| Form | Closed | Open |
|---|---|---|
| word sequence, lambda, `if` | ✅ | ✅ |
| `match`, colon call, dot chain, `rescue`, `allow`/`with` | — | ✅ |

Nothing about the *shape* of `match` explains why it is disallowed where `if` is allowed;
the line is arbitrary from the user's seat. The friction shows up in ordinary code:

```jo
// today: a colon call may not appear in an argument list
foo(bar: 1, 2)          // rejected

// so the value must be lifted out and named
val r = bar: 1, 2
foo(r)
```

The distinction is really doing two unrelated jobs, and only one is worth keeping:

1. **Grammatical disambiguation** — stopping a comma-separated argument list from colliding
   with a comma-separated colon call.
2. **Style** — discouraging nestings that are legible to the parser but hard for a human.

The pain is that job 2 is welded to job 1. Once they are separated, job 1 turns out to be a
*single* genuine ambiguity — the commas of an inline colon call — and everything else is a
restriction with no grammatical basis. This proposal keeps job 1 as a one-line rule and
drops the rest.

## Rationale: what actually collides

The distinction that matters is not open vs. closed. It is **what token ends an
expression**, and whether a form reuses that same token as one of its own separators.

The colon call is the whole story, because it is the only form with two shapes that carry
*different* top-level delimiters:

- an **inline** colon call separates its arguments with **commas**:

  ```jo
  foo: a, b
  ```

- an **indented** colon call separates its arguments with **newlines**:

  ```jo
  foo:
    a
    b
  ```

Drop either one into a comma-separated list. The inline form's commas merge with the list's
commas — `f(foo: 1, 2)` could mean `f(foo(1, 2))` or `f(foo(1), 2)`, and nothing on the page
decides. The indented form has no such problem: its arguments are separated by indentation,
so the surrounding commas remain unambiguously the list's own.

That single asymmetry accounts for every restriction worth keeping. Every other open-only
form — dot chains, `match`, `rescue`, `allow`, `with` — is delimited by indentation or by
its own keywords, never by a comma, so none of them ever collides. Their exclusion from
delimited positions was never grammatically necessary.

## Specification

### The rule

> A form is admissible in a context unless its top-level delimiter is the same token that
> terminates the context.

Contexts are classified by their terminator. Most terminators — a closing bracket, a
keyword — are matched by no form's delimiter, so most positions admit every form. Only two
contexts are distinctive:

| Context | Terminator | Effect | Examples |
|---|---|---|---|
| **Comma-list** | comma / closing bracket | removes the inline colon call | `f(a, b)`, `[a, b]`, `arr[i, j]`, inline colon args, named args |
| **Block** | dedent | adds the phrase-only forms | block phrases, indented colon arguments |

- A **comma-list** is terminated by a comma. The only form whose top-level delimiter is a
  comma is the inline colon call, so that is the one form it excludes. Words, lambdas, `if`,
  `match`, dot chains, and indented colon calls all remain admissible — none separates its
  own parts with commas.
- A **block** is terminated by a dedent relative to its own indentation. Nested forms open
  their indentation regions at deeper columns, so they never reach that dedent prematurely
  and compose freely. A block admits every form, plus the phrase-only statements (`val`,
  `def`, `while`, assignment, …).
- Every other position — a fence `(e)`, an interpolation `\{e}`, the condition of
  `if`/`while`/`match`/`for` — has a terminator (a bracket or a keyword) that no form's
  delimiter matches, and so admits every form with nothing added or removed.

### Grammar

The rule reduces to two productions. `simple_expr` is every form except the inline colon
call; `expr` adds it back:

```ebnf
simple_expr = words
            | lambda
            | if_expr                       (* with or without else *)
            | match_expr
            | indented_colon_call
            | dot_chain
            | allow_expr | with_expr
            | rescue_expr

expr = simple_expr | inline_colon_call

inline_colon_call = atom NS ":" simple_expr {"," simple_expr}   (* commas stay on the ":" line *)

(* context usage: comma positions take simple_expr, everything else takes expr *)
comma_arg    = simple_expr                    (* call args, list/bracket, named args, inline colon args *)
block_phrase = expr | phrase_only             (* a block additionally admits statements *)
                                              (* a fence (e), interpolation \{e}, and condition take expr *)

phrase_only  = assign | while | for | return | break | continue
             | val_def | var_def | pat_val_def | auto_def | fun_def | pat_def
```

This replaces the `expr` / `open_expr` split in the current grammar, and it follows the
file's existing convention that the `simple_` variant is the restricted one
(`simple_type ⊂ type`, `simple_pattern ⊂ pattern`). The single alternative separating `expr`
from `simple_expr` — the inline colon call — *is* the entire content of the former
open/closed distinction.

Two properties are worth drawing out, because the ergonomics depend on them:

- **Self-reference.** An inline colon call's arguments are `simple_expr`, so an inline colon
  call cannot appear inside another one's argument list. Nesting requires parentheses
  (`foo: a, bar(c, d)`) or the indented form. No separate "no nested colon" rule is needed;
  it falls out of the production.
- **Commas stay on the `:` line.** The arguments of an inline colon call keep their commas
  on the same line as the `:` — an invariant carried over from today's grammar. Because a
  form that opens an indentation region necessarily ends its line, no comma can follow it,
  so any indentation-opening argument is automatically the *last* one. This is what makes a
  trailing lambda or trailing block work without a special "last argument" rule (see
  [Consequences](#consequences)).

Against [Expression Forms](../language/expressions/expression-forms.md): the `Open only`
column is deleted, and every row of its *Expressions* table becomes one `expr`.

### Form terminators

The design is a function of a single column — the token that ends each form:

| Form | Top-level terminator | Excluded from |
|---|---|---|
| words, `is`, `as`, prefix application | none — self-delimited | — |
| lambda | none, or dedent (block body) | — |
| `if … then … else …` | none — bracketed by `then`/`else` | — |
| `if … then …` (no `else`) | dedent (block body) | — |
| **inline** colon call `f: a, b` | **comma** | comma-lists (parenthesize or use the indented form) |
| **indented** colon call | dedent | — |
| dot chain | dedent | — |
| `match` | dedent (cases opened by `case`) | — |
| `allow` / `with` | dedent (block after `in`) | — |
| `rescue` | none (inline body) or dedent (block) | — |

Only the inline colon call carries a terminator that can collide — the comma. Every other
form is self-delimited or dedent-delimited, and composes in every context.

### Indentation anchoring

The two forms that continue across lines without an introducing keyword — the indented
colon call and the newline-leading dot chain — are anchored on their **head**, not on the
surrounding context:

- an indented colon call's arguments are indented relative to the head that precedes `:`;
- a dot chain's continuation lines are indented relative to the head atom that precedes the
  first `.`.

Because the anchor is the head, it is well-defined wherever the form appears — a block
phrase, an indented colon argument, or nested inside a bracket. A bracket does not need to
establish an indentation frame; the head already does. The bracket merely contributes its
closing token as an additional terminator, which never competes with the anchor:

```jo
f(xs
    .map(g)      // anchored on xs
    .sum)        // also ends at )

f(g:
    a            // anchored on g
    b)
```

No context-specific indentation rule is required: the head-relative behavior these forms
already have carries into every context unchanged.

## Consequences

Several things that would otherwise need dedicated rules are simply instances of the one
rule.

**Colon calls in parenthesized calls.** An indented colon call is admissible as any argument
of a comma-list — its delimiter is indentation, and the bracket bounds it:

```jo
f(g:
    a
    b)
```

The inline colon call is not, because its commas would merge with the call's:

```jo
f(foo: 1, 2)     // rejected — write f(foo(1, 2))
```

**Nested colon calls.** An inline colon call's arguments are themselves a comma-list, so the
same rule forbids an inline colon call there but permits an indented one:

```jo
foo: a, bar(c, d)   // inline argument is a comma context → parentheses required

foo: a, bar:        // indented argument is dedent-bounded → nesting is fine
  c
  d
```

Grouping stays visible in the layout: one colon call's commas never share a line with
another's.

**Trailing lambdas and trailing blocks.** These need no "last argument" exception. A lambda
body, a `match`, or an indented colon call is delimited by `=>`, `case`, or indentation —
never by a comma — so each is an ordinary admissible argument. And because such a form ends
its line, nothing can follow it; it lands last as a consequence, not by a rule:

```jo
list.fold: 0, (acc, x) =>
  acc + x

each: items, x =>
  log(x)
  process(x)
```

**Indentation already lives inside brackets.** An `if`/`else` with block bodies is already a
legal call argument today, so brackets never "suppressed" indentation to begin with. A
block-introducer (`then`, `=>`, `in`, `case =>`) opens its region wherever it appears. The
rule needs no clause for this — it is simply not a collision.

## Why the inline colon call is the one sensitive form

There is a deeper reason the inline colon call, and only it, must be constrained: **it is
the sole comma-list with no closing delimiter.** Every other comma-list is bounded — a call
ends in `)`, a list in `]`, an index in `]`, an `allow`/`with` binding list in `in`. Their
contents can lean on that closing token. The inline colon call's right edge is nothing but
the end of the line.

This is why its commas must stay on the `:` line: it is the one comma-list that cannot be
rescued by a bracket. And it is why the inline/indented distinction of the colon call is
load-bearing rather than cosmetic — the inline form is the single thing a comma-list must
exclude, and the indented form is admissible everywhere its indentation can be bounded,
which is everywhere.

## Impact on existing code

The change is a strict enlargement of the grammar: the new productions only *add*
alternatives in positions that previously rejected them. No program that is valid today
becomes invalid.

- Any expression may now appear as a condition: `if xs.filter(p).any then …`, or a `match`
  or colon call used as a condition.
- `match`, `allow`, `with`, dot chains, and indented colon calls may now appear as arguments
  in parenthesized calls, list literals, and fences, bounded by the bracket.
- Trailing lambdas and trailing blocks work uniformly across every call form.
- The only rejections that remain are the genuine collisions — an inline colon call directly
  inside a comma-list (`f(foo: 1, 2)`, `foo: bar: 1, 2`). These were already rejected under
  the open/closed scheme; the difference is that they are now rejected for a stateable reason
  (*commas do not nest*) rather than by membership in an arbitrary category.

## Design decisions

**Inline colon calls keep multiple arguments.** `send: user, message` remains legal at
phrase level. The cost is that the inline colon call stays the rule's single casualty inside
a comma-list — nesting it needs parentheses or the indented form. Restricting inline colon
calls to a single argument would have removed even that casualty (a one-argument colon call
carries no comma, so it would compose everywhere), but the multi-argument form is a core
ergonomic and worth the one narrow exclusion.

**Conditions admit the full `expr`.** The condition of `if`/`while`/`match`/`for` is not a
special context; its keyword terminator matches no form's delimiter, so it admits every
form, exactly as a fence or interpolation does. A `match` or an indented colon call may be
used as a condition. A pathologically nested else-less `if` such as `if if a then b then c`
still parses deterministically — if the result reads badly, that is the author's problem to
fix, not a grammatical ambiguity; the compiler is never in doubt.

## Alternatives considered

**Keep open/closed, widen both tiers until they overlap.** Add targeted exceptions — let
`if`/`while`/`match` conditions be open, let `with`/`allow`/`rescue` appear in closed
positions — so that the two sets coincide for common code. This reduces friction but does
not remove it: it trades one list of restrictions for a longer list of exceptions, and still
presents the constraint as two arbitrary tiers rather than one rule. The delimiter-collision
rule subsumes every exception this option would enumerate.

**Cancel the distinction entirely and let users own the style.** Allow every form everywhere
and treat awkward nestings as the programmer's responsibility. This under-delivers on its
own terms: its two headline targets — nested inline colon calls, and inline colon calls
inside parenthesized calls — are exactly the comma-collision cases, which cannot be admitted
without either an invisible comma/colon associativity rule or genuinely reader-ambiguous
grouping. The delimiter-collision rule keeps precisely those two cases out while freeing
everything else — achieving the goal of this alternative without its ambiguity.
