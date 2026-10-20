---
author: Fengyun Liu
status: Draft
created: 2026-10-20
title: Drop top-level Int operators
---

# JIP 0008 — Drop top-level Int operators

<JipMeta />

## Summary

Remove the top-level operator functions `+`, `-`, `*`, `/`, `%`, `>`, `<`,
`>=`, `<=`, `==`, and `!=` on `Int` from the standard library. The
corresponding `Int` methods remain, so arithmetic and comparison on values of
known type are unchanged. A lambda parameter whose type could only be guessed
from arithmetic or comparison in its body now needs a type annotation.

## Motivation

The standard library defined each of these operators twice: as a method of
`Int`, and as a top-level function forwarding to that method:

```jo
def (a: Int) + (b: Int): Int = a + b
```

The top-level functions existed only to help type inference. A dotless infix
call `a + b` first looks for a member `+` on the type of `a`. The top-level
function is used only when that lookup fails, which in practice means the type
of `a` is not yet known. The top-level `+` then fixes `a` to `Int`:

```jo
def app2[X, Y](f: X => Y, x: X): Y = f x

app2 (x => x + x) 5      // `x` becomes `Int` through the top-level `+`
```

This proposal removes the functions for three reasons:

1. **Explicitness over implicitness.** The functions commit an unknown type
   to `Int` even when the program says otherwise, and report the conflict far
   from its cause.
2. **Arbitrary coverage.** Only `Int` and only some operators have the
   fallback. It cannot be extended to other types.
3. **Simplicity and uniformity.** Without the functions, each operator is
   defined once, as a member, and `Int` operators resolve like the operators
   of every other type.

## Justification

### Explicitness over implicitness

With the top-level functions, the following program is rejected:

```jo
app2 (x => x + x) "ab"
```

The lambda is typed before the argument `"ab"`, so `x` is still unknown when
`x + x` is typed. The top-level `+` fixes `x` to `Int`, and the compiler then
reports `Expect type Int, found = String` at `"ab"`, which is correct code. The
same happens for `app2 (x => x * x) 1.5`. The fallback does not infer the type
from the program. It picks `Int` and lets the rest of the program disagree.

This is the kind of guessing ruled out by the design principle of
[explicitness over implicitness](../language/design-principles.md#_4-explicitness-over-implicitness):

> The compiler should not perform complex guessing. Users should make their
> intent clear when it's not obvious.

When nothing in the program determines the type of `x`, its intent is not
obvious, and the user should state it.

Without the fallback, the error is reported inside the lambda, where the
missing information is, and the fix is local:

```jo
app2: (x: String) => x + x, "ab"
```

### Arbitrary coverage

The fallback covers `Int` but not `Long`, `Float`, `Byte`, `Char`, or `String`.
It covers arithmetic and comparison but not the bitwise operators `&`, `|`,
`^`, `<<`, and `>>`. A reader cannot predict from the language rules which
lambda bodies type-check without annotations: `x => x + 1` does, while
`x => x & 1` does not.

The gap cannot be closed. A name has one top-level definition in a scope, so
there can be only one top-level `+`, and it must pick one type. Any choice
privileges that type through the library rather than through a language rule.

### Simplicity and uniformity

With the functions, every arithmetic and comparison operator on `Int` was
defined twice: once as a method and once as a top-level function in the scope
of every program. `Int` was the only type with such a second definition, so
`a + b` was resolved by a different path when the type of `a` was unknown.

Without them, each operator is defined once, as a member of its type. The
expression `a + b` means the same thing for `Int`, `Long`, `Float`, `String`,
and user-defined classes: a call of the member `+` of `a`. The standard
library no longer gives `Int` a special status. The only top-level
operator left in the standard library is `~`, which has no member counterpart.

## Consequences

This is a source-incompatible change. A lambda whose parameter type was fixed
only by an `Int` operator in its body must annotate the parameter:

```jo
val f = x => x + x            // now rejected
val f = (x: Int) => x + x
```

Lambdas with an expected type are unaffected, including the common cases of
collection methods and typed definitions:

```jo
list.map: x => x * 2
xs.fold: 0, (acc, x) => acc + x
val double: Int => Int = x => x * 2
```

A parameter can still be inferred from its use as an argument of a function
with a typed parameter:

```jo
def square(n: Int): Int = n * n

val quad = x => square(square(x))   // `x` inferred as `Int`
```

In the test suite, 3 of 308 positive tests needed changes. No published
library depends on the removed functions. The
diagnostic for the rejected form is currently `Undefined term name +`. A
message suggesting a parameter annotation would be a useful improvement.

## Implementation

Implemented in [#174](https://github.com/typescope/jo/pull/174).
