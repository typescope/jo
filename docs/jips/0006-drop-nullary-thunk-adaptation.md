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

`assert` is another use case. Its message parameter formerly had type
`() => String`, so successful assertions could skip message construction:

```jo
// With the former thunk parameter:
assert(cond, () => "unexpected state: \{state}")  // explicit lambda
assert(cond, "unexpected state: \{state}")        // implicit wrapping
```

Both forms avoid formatting the string when the conditions hold. Adaptation
let the message read like an ordinary value while retaining that optimization.

This proposal removes the feature for three reasons:

1. **Semantic clarity.** An explicit lambda makes delayed or repeated evaluation
   visible at the use site.
2. **Marginal utility.** The main library uses no longer depend on adaptation,
   and the remaining convenience chiefly concerns diagnostic construction.
3. **Optimal evolution.** Adding the feature later is compatible, while keeping
   it will be a life-long debt if it is not the optimal choice.

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
`Option.getOrElse` and `Result.getOrElse` can accept an ordinary value of type
`T`. Requiring a thunk for every default complicates these simple calls merely
to avoid computing an unused fallback. Programmers already have facility to
do that explicitly if they want the micro-optimization.

`assert` presents a stronger case for implicit deferral. Its message reads
naturally as a value, yet constructing it on every successful assertion wastes
work. The motivation for deferring is performance.

The same benefit could appear in user-defined logging and validation
helpers. They can skip constructing unused diagnostic strings. The motivation is
again performance. But if the performance matters there as micro-optimization,
it might be better to be made explicit to avoid breaking the optimization accidentally
in refactoring. In addition, the type `String | (() => String)` could be used to
support address both usability and performance concerns.

Inventing a general language feature primarily for performance reasons is a
trap in language design. Here, the benefit chiefly concerns diagnostic construction,
while the feature compromises semantic clarity. That is too high a price
for this narrow optimization.

### Optimal evolution

Delaying the feature keeps the decision reversible. If future use cases
justify implicit wrapping, it is easy to add. The compiler would synthesize
lambda trees, and no changes to SAST are needed.

Delaying also leaves room for better designs. For example, it is possible to enhance
duck types to adapt a value from `T` to `() => T`. That would make the feature
less invasive: the library author can control when such adapation should happen.

Keeping the feature now makes the decision permanent once users and libraries
depend on it. Removing it or changing it for better designs would be a breaking change.

This is the argument by C. A. R. Hoare in [*The Emperor's Old Clothes*](https://dl.acm.org/doi/10.1145/358549.358561):

> A feature which is omitted can always be added later, when its design and its
> implications are well understood. A feature which is included before it is
> fully understood can never be removed later.

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
`SourceLocation`.

Given the special status of `assert` in a language and its wide usage, we believe
intrinsification is a good choice.

## Alternatives considered

**Keep adaptation for diagnostic messages.** This avoids lambda syntax in
user-defined logging and diagnostic helpers, but retains a general evaluation
rule for a narrow convenience. Restricting the rule to strings would be arbitrary:
structured diagnostics have the same needs, while many string-producing
operations should remain visibly deferred.
