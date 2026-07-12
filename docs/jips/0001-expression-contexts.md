# JIP 0001 — Replacing open/closed expressions with delimiter collision

**Status:** Draft &nbsp;·&nbsp; **Created:** 2026-07-12

## Summary

Jo currently splits expressions into two grammatical tiers — **closed** (usable in
delimited positions: `(...)`, call arguments, `\{...}`) and **open** (usable as phrases
and indented colon-call arguments). Colon calls, dot chains, `match`, and `allow`/`with`
are open-only.

This document proposes replacing that two-tier scheme with a single principle:
**a form is admissible in a context unless its top-level delimiter is the same as the
context's terminator.** The open/closed distinction dissolves into this one rule, the set
of legal programs grows to match syntactic intuition, and the one genuine constraint that
remains — colon calls versus commas — is preserved for a reason users can state.

## Motivation

The closed/open distinction pays a real usability price: users must remember which forms
are allowed in which positions, instead of relying on syntactic intuition. The two-way
table (`match` is open-only, `if` is not; `rescue` is open-only; colon calls are
open-only) reads as an arbitrary list.

But the distinction is actually doing *two* unrelated jobs, and only one of them is worth
keeping:

1. **Grammatical disambiguation** — keeping comma-delimited argument lists from colliding
   with colon-delimited ones.
2. **Style** — forbidding legible-but-ugly nestings.

The pain users feel is entirely about job #2 being conflated with job #1. Job #1 turns out
to be a *single* genuine ambiguity — the inline colon call's commas; everything else is a
restriction with no grammatical justification. Once we isolate job #1 as a one-line rule,
the rest of the restrictions can be dropped.

## The key observation

The line that matters is not *closed vs. open*. It is **what terminates the expression**,
and whether the form's own top-level delimiter clashes with it.

Consider the colon call. It has two shapes, and they have *different top-level delimiters*:

- **Inline** colon call `foo: a, b` — its arguments are separated by **commas**.
- **Multiline** colon call — its arguments are separated by **newlines / indentation**:

  ```jo
  foo:
    a
    b
  ```

In a comma-separated context, the inline form collides (its commas merge with the
surrounding ones) but the multiline form does not (its delimiter is indentation, not
comma). That single fact explains every restriction worth keeping — and reveals that the
rest are unjustified.

## Design

### Contexts

Every position is characterized by what terminates it. Most terminators match no form's
delimiter, so most positions admit the full `expr` and are not worth naming — a fence
`(e)`, an interpolation `\{e}`, and the condition of `if`/`while`/`match`/`for` all take
everything. Only two positions are special:

| Context | Terminator | Effect | Examples |
|---|---|---|---|
| **Comma-list** | comma / bracket | removes one form | `f(a, b)`, `[a, b]`, `arr[i, j]`, inline colon args, named args |
| **Block** | dedent | adds the phrase-only forms | phrases in a block, indented colon arguments |

### The rule

> **A form is admissible in a context unless its top-level delimiter is the same as the
> context's terminator.**

That is the entire design. Only one context ever removes a form:

- **Comma-list** — terminator is the comma. Excludes exactly the forms whose top-level
  delimiter is a comma: the **inline colon call**. Everything else is admitted — words,
  lambdas, `if`, `match`, dot chains, and the **multiline** colon call — because none of
  them separates its own top-level parts with commas.

- **Block** — terminator is dedent, established relative to the block's own indentation.
  Nested forms open their indentation regions at deeper levels, so they nest freely. The
  full `expr` is admissible, plus the phrase-only forms (statements).

Every other position — a fence, an interpolation, a condition — has a terminator that no
form's delimiter matches, so it admits the full `expr` with nothing added or removed.

### Grammar

The entire design is two productions. `simple_expr` is every form except the inline colon
call; `expr` adds that one form back. This follows the file's existing convention where the
`simple_` variant is the restricted one (`simple_type ⊂ type`, `simple_pattern ⊂ pattern`)
— and it replaces the two-nonterminal `expr` / `open_expr` split of the current grammar.

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

inline_colon_call = atom NS ":" simple_expr {"," simple_expr}   (* its args are a comma context *)

(* context usage — comma positions take simple_expr, everything else takes expr *)
comma_arg    = simple_expr                    (* call args, list/bracket, named args, inline colon args *)
block_phrase = expr | phrase_only             (* block additionally admits statements *)
                                              (* fence (e), interpolation \{e}, condition: expr *)

phrase_only  = assign | while | for | return | break | continue
             | val_def | var_def | pat_val_def | auto_def | fun_def | pat_def
```

The single alternative that separates `expr` from `simple_expr` — the inline colon call —
*is* the entire difference the open/closed distinction was trying to capture. Note the
self-reference: an inline colon call's own arguments are `simple_expr`, which is exactly why
it cannot nest inside another one without parentheses.

Concretely against [Expression Forms](../language/expressions/expression-forms.md): the
`Open only` column is deleted, and every row of its *Expressions* table becomes one `expr`.

### Form terminators

The whole design is a function of one column — what token ends each form:

| Form | Top-level terminator | Excluded from |
|---|---|---|
| words, `is`, `as`, prefix apply | none — self-delimited | — |
| lambda | none, or dedent (block body) | — |
| `if … then … else …` | none — bracketed by `then`/`else` | — |
| `if … then …` (no `else`) | dedent (block body) | — |
| **inline** colon call `f: a, b` | **comma** | comma contexts (parenthesize or go multiline) |
| **multiline** colon call | dedent | — |
| dot chain | dedent | — |
| `match` | dedent (cases opened by `case`) | — |
| `allow` / `with` | dedent (block after `in`) | — |
| `rescue` | none (inline body) or dedent (block) | — |

Only the inline colon call carries a terminator that collides — the comma. Everything else
is self-delimited or dedent-delimited and composes in every context.

### Consequences that fall out for free

Several things that would otherwise need special rules are simply instances of the one
rule:

- **Colon calls in parenthesized calls.** A **multiline** colon call is admissible as any
  argument of a comma-list, because its delimiter is indentation, not comma, and the
  bracket bounds it:

  ```jo
  f(g:
      a
      b)
  ```

  The **inline** colon call is not, because its commas would merge:

  ```jo
  f(foo: 1, 2)     // rejected — write f(foo(1, 2))
  ```

- **Nested colon calls.** The arguments of an inline colon call are themselves a
  comma-list, so the same rule forbids an inline colon call there — parentheses are
  required — but permits a multiline one:

  ```jo
  foo: a, bar(c, d)   // inline last arg is comma-context → parens required

  foo: a, bar:        // multiline last arg is dedent-bounded → nested colon OK
    c
    d
  ```

  This makes grouping visible in layout: a colon call's inline commas never share a line
  with another colon call's.

- **Trailing lambdas and trailing blocks.** These need no "last argument" exception. A
  lambda body, a `match`, or a multiline colon call is delimited by `=>` / `case` /
  indentation — never by a comma — so all are admissible as arguments. And because opening
  an indentation region ends the line, nothing can follow such an argument, so it lands
  last as a *consequence*, not by a rule:

  ```jo
  list.fold: 0, (acc, x) =>
    acc + x

  each: items, x =>
    log(x)
    process(x)
  ```

- **Indentation already lives inside brackets.** An `if`/`else` with block bodies is
  already a legal call argument today, so brackets do not "suppress" indentation. A
  block-introducer (`then`, `=>`, `in`, `case =>`) opens its region wherever it appears,
  bracketed or not. The rule needs no clause for this; it is not a collision.

### Why the inline colon call is the sensitive form

Of all the comma-list contexts, only one has **no closing delimiter**: the top-level
inline colon call. Every other comma-list ends in a bracket (`f(…)`, `[…]`, `arr[…]`) or a
keyword (`with … in`, `allow … in`), which bounds its contents. The inline colon call's
right edge *is* the line.

This is why the inline colon call, uniquely, must keep its top-level commas on one line:
it is the sole comma-list that cannot lean on a closing token. It is also why the
inline/multiline distinction of the colon call is load-bearing rather than cosmetic — the
inline form is the one thing the comma-list rule excludes, and the multiline form is
admitted everywhere the indentation can be bounded.

## What changes for the user

- Any expression may appear as a condition: `if xs.filter(p).any then …`,
  `match` and colon calls as conditions.
- `match`, `allow`, `with`, dot chains, and multiline colon calls may appear as arguments
  in parenthesized calls, list literals, and fences — bounded by the bracket.
- Trailing lambdas and trailing multiline blocks work uniformly across all call forms.
- The *only* things still rejected are the genuine collisions: an **inline** colon call in
  a comma-list (`f(foo: 1, 2)`, `foo: bar: 1, 2`). Those require parentheses or the
  multiline form — and now for a reason the user can state ("commas don't nest"), not
  because of a category to memorize.

## Alternatives considered

**Keep open/closed, widen both to overlap.** Add small exceptions — allow `if`/`while`/
`match` conditions to be open, allow `with`/`allow`/`rescue` in closed positions — so the
two sets mostly coincide. This reduces the friction but does not remove it: it replaces one
list of restrictions with a longer list of exceptions, and still frames the constraint as
two arbitrary tiers rather than one collision rule. The delimiter-collision rule subsumes
every exception this option would enumerate.

**Cancel the distinction entirely, users own the smell.** This under-delivers on its own
terms: its two concrete targets — nested inline colon calls and inline colon calls in
parenthesized calls — are exactly the comma-collision cases, which cannot be admitted
without either an invisible comma/colon associativity rule or genuinely reader-ambiguous
grouping. The delimiter-collision rule keeps precisely those two cases out while freeing
everything else.

## Decisions

- **Inline colon calls keep multiple arguments.** `send: user, message` stays legal at
  phrase level. The consequence is that the inline colon call remains the rule's single
  casualty in comma contexts — nesting it requires parentheses (`f(send(user, message))`)
  or the multiline form. Restricting inline colon to one argument would have removed the
  casualty entirely, but the multi-argument ergonomic is worth the one narrow exclusion.
- **Conditions admit the full `expr`.** The condition of `if`/`while`/`match`/`for` is not
  a special context — its terminator (a keyword) matches no form's delimiter, so it admits
  everything, exactly like a fence or interpolation. A `match` or a multiline colon call as
  a condition is legal. A nested else-less `if` (`if if a then b then c`) parses
  deterministically; if the layout reads badly that is the author's problem to fix, not an
  ambiguity — the compiler is never in doubt.

## Indentation anchor

Multiline colon calls and newline-leading dot chains are anchored on their **head**, not on
the surrounding context:

- an indented colon call's arguments are indented relative to the head before `:`;
- a dot chain's continuation lines are indented relative to the head atom before the first `.`.

Because the anchor is the head, it is well-defined wherever the form appears — a block
phrase, an indented colon argument, or nested inside a bracket. A bracket does not need to
establish an indentation frame; the head already does. The bracket only contributes `)` as
an additional terminator, alongside dedent and comma, which never competes with the anchor:

```jo
f(xs
    .map(g)      // anchored on xs
    .sum)        // ends at )

f(g:
    a            // anchored on g
    b)
```

So no context-specific indentation rule is required; the head-relative rule the forms
already use carries into every context unchanged.
