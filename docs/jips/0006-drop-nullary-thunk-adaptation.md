---
author: Fengyun Liu
status: Draft
created: 2026-10-07
title: Drop nullary thunk adaptation
---

# JIP 0006 — Drop nullary thunk adaptation

<JipMeta />

> A feature which is omitted can always be added later, when its design and its
> implications are well understood. A feature which is included before it is
> fully understood can never be removed later.
>
> — C. A. R. Hoare, [*The Emperor's Old Clothes*](https://dl.acm.org/doi/10.1145/358549.358561),
> *Communications of the ACM*, February 1981, p. 81.

## Summary

This proposal drops **nullary thunk adaptation**, which implicitly wraps an
expression `e` in `() => e` when a nullary function is expected. Deferred
computation is written explicitly:

```jo
val next: () => Int = counter.next()         // rejected
val next: () => Int = () => counter.next()   // explicit
```

## Motivation

To improve usability, when a parameter expects the type `() => T`, the caller
can supply `e` of type `T`, and the compiler automatically wraps it in `() => e`.

One motivation for the feature was `getOrElse`. Its fallback was a
function of type `() => T`, called only when the value was absent. Adaptation
allowed callers to supply a simple default without wrapping it themselves:

```jo
// With the former thunk parameter:
option.getOrElse(() => 0)  // explicit lambda
option.getOrElse(0)        // implicit wrapping
```

`assert` provided another use. Its message parameter formerly had type
`() => String`, so successful assertions could skip message construction:

```jo
// With the former thunk parameter:
assert(cond, () => "unexpected state: \{state}")  // explicit lambda
assert(cond, "unexpected state: \{state}")        // implicit wrapping
```

Both forms avoided formatting the string when the condition held. Adaptation
let the message read like an ordinary value while retaining that optimization.

This proposal removes the feature for three reasons:

1. **Semantic clarity.** An explicit lambda makes delayed or repeated evaluation
   visible at the use site.
2. **Marginal utility.** The main library uses no longer depend on adaptation,
   and the remaining convenience chiefly concerns diagnostic construction.
3. **Optimal evolution.** Adding the feature later is compatible, while keeping
   it commits future versions to supporting it once users depend on it.

## Justification

### Semantic clarity

Convenient syntax comes at a semantic cost. In the declaration from the summary,
the apparent call to `counter.next()` does not happen at the declaration.
It happens on every invocation of `next()`. The expected type silently changes
when an expression runs and how often it runs. At a function call, the reader
must inspect the callee's parameter type to discover this behavior.

Writing `() =>` gives the deferred computation a visible boundary. This follows
Jo's principles of semantic clarity and explicitness, and makes the distinction
between computing a value and passing a computation apparent at the use site.

### Marginal utility

For a constant fallback such as `0`, there is no useful work to defer.
In fact, it is an overkill for `Option.getOrElse` and `Result.getOrElse` to
accept a thunk of type `() => T` instead of `T`. If programmers want to delay
the default computation, they can do that explicitly with a `match`, which is better.

The use case for `assert` seem to be justified. But in essence, it is a feature
for performance improvement, which is a red flag in language design.

User-defined logging and validation helpers can still benefit from skipping
unused messages. If message construction has no observable effects, delaying
it changes only the cost of the call. If construction does have effects, an
explicit lambda helps the reader see that those effects may never occur.

The benefit is most apparent for diagnostic strings, which are often discarded
and may be expensive to construct. Structured log records and diagnostic trees
can benefit for the same reason. These descriptions may never be used,
whatever type represents them.

Other lazy APIs, such as retries and transactions, have a different purpose.
A retry repeats a computation, and a transaction runs it within a controlled
scope. An explicit lambda shows which work is subject to that behavior.
Omitting it saves syntax but hides useful information. These APIs justify
passing computations as functions, but provide little reason to wrap them
implicitly.

Performance is a legitimate design concern, but concise diagnostic-message
construction does not justify a general rule that silently changes evaluation
throughout the language. Explicit lambdas preserve both the optimization and
the visible boundary.

### Optimal evolution

Delaying the feature keeps the decision reversible. If future use cases
justify implicit wrapping, it is easy to add. The compiler would synthesize
existing lambda nodes, so no new SAST representation is needed. Previously
serialized libraries would retain their meaning without a compatibility change.

Keeping the feature now makes the decision permanent once users and libraries
depend on it. Removing it would break their source code. Preserving source
compatibility would require supporting the rule forever, even if its design
later proves undesirable. Explicit lambdas let us wait until the need and the
implications are better understood.

## Consequences

This is a source-incompatible change. A caller that relies on implicit
deferral must write `() => e` to preserve its behavior.

`assert` is now a compiler intrinsic. The compiler lowers
`assert(cond, msg)` to `if !cond then abort(msg)`, so its message and source
location are evaluated only on failure:

```jo
assert(cond, expensiveMessage())
```

Assertions therefore retain concise call syntax without depending on thunk
adaptation. Successful assertions do not allocate a message closure or a
`SourceLocation`. User-defined lazy APIs require explicit lambdas after
removal. Implicit wrapping is rejected. Explicit lambdas preserve deferred,
repeated evaluation.

## Alternatives considered

**Keep adaptation for diagnostic messages.** This avoids lambda syntax in
user-defined logging and diagnostic helpers, but retains a general evaluation
rule for a narrow convenience. Restricting the rule to strings would be arbitrary:
structured diagnostics have the same needs, while many string-producing
operations should remain visibly deferred.
