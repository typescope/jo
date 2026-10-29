---
author: Fengyun Liu
status: Rejected
created: 2026-10-28
title: Default equality operators
---

# JIP 0009 — Default equality operators

<JipMeta />

::: info Decision

This proposal is rejected for the following reasons:

1. **Feature creep.** The original goal was to avoid repeating the negation of
   `==` in every `!=` method. Adding a global `==` for symmetry expanded the
   proposal into generic comparison through an implicit `Eq[T]`. This goes
   against Jo's principle of explicitness over implicitness. Generic code
   should make its equality operation explicit, rather than acquire concise
   but indirect operator syntax from an ambient implicit.
2. Jo's design also intentionally guards against language-level universal equality between
   two arbitrary values or an arbitrary type. Equality is an important program
   design decision that belongs to programmers. Adding a universal `==` and
   `!=` operators blurs the line, even if they are guarded by `Eq[T]`.
3. **Semantic clarity.** A simple `a == b` can mean a member call or a global
   function using an auto instance, depending on lookup and the operand
   types. The interaction between `jo.==` and `a.==` makes the expression
   harder to understand. It can also make `==` and `!=` use different equality
   operations, depending on the subtle precedence rules in resolving `==`.
4. **Engineering simplicity.** Introducing implicit instances and synthesized
   adapters, then teaching the compiler to undo them, adds unnecessary
   complexity. Specializing an intrinsic `!=` into a direct equality call and
   negation expresses the original requirement more simply.

Generality and symmetry alone do not justify a feature. Semantic clarity and
engineering simplicity take precedence.

[JIP 0010](./0010-intrinsic-not-equals.md) proposes the narrower intrinsic
design. The original proposal is retained below as a record of this decision.

:::

## Summary

Add global `==` and `!=` functions to the standard library:

```jo
def (a: T) == [T](b: T)(auto eq: Eq[T] with [[T].==]): Bool = eq.equal(a, b)
def (a: T) != [T](b: T)(auto eq: Eq[T] with [[T].==]): Bool = !eq.equal(a, b)
```

A class's own `==` or `!=` method takes precedence over the corresponding
global function, following the existing rules for infix operators. Ordinary
auto resolution selects an `Eq[T]`, synthesizing it from `T.==` by default. The compiler
optimizes global calls whose equality instance is the compiler-generated
adapter `(a, b) => a == b` into `a == b` or `!(a == b)`, respectively.

## Motivation

Classes commonly define `==` and repeat its negation as `!=`. The standard
library does this for primitive types and for `List`, `Option`, `Result`, and
`Set`. A class that defines only `==` can participate in equality-based
collections but still cannot use the corresponding infix `!=` expression.

The matching global `==` provides symmetry and lets generic code use operator
syntax with an `Eq[T]` instance:

```jo
def same[T](a: T, b: T)(auto eq: Eq[T]): Bool = a == b
```

The global functions express both operations through Jo's existing `Eq`
interface and member-first lookup. Optimizing the synthesized adapter removes
the extra function call, equality adapter, and interface dispatch for
common cases.

## Proposal

### Global definitions

The functions are defined the standard library, which are imported by default.
Lookup, type inference, and auto resolution follow the existing rules.
A class's own operator method takes precedence over the corresponding global function as usual.

### Compiler optimization

When a call to the global `jo.==` or `jo.!=` receives the compiler-synthesized
equality adapter `(x, y) => x == y`, inline the function and adapter to produce,
respectively:

```jo
a == b
!(a == b)
```

The equality shown here is the already-resolved member call.

This removes the wrapper call and adapter overhead. The unoptimized library
functions have the same semantics, so correctness does not depend on inlining.

Calls using custom equality instances or a class's own `==` or `!=`
retain their ordinary behavior.

## Justification

The proposal follows
[explicitness over implicitness](../language/design-principles.md#_4-explicitness-over-implicitness):
the programmer still supplies equality through a method or an `Eq` instance.
The global functions expose that equality and its negation, while existing
member definitions remain authoritative.

We avoid adding global operators to standard library.
[JIP 0008](./0008-drop-top-level-int-operators.md) removed monomorphic `Int`
operator fallbacks.
However, adding generic `==` and `!=` is justified by their applicability to
any type with an `Eq` instance, their symmetry, and their wide usage.

## Compatibility

Existing member implementations and successfully resolved operator calls
retain their behavior. Code that previously failed because `==` or `!=` was
missing can now use the global fallback when an `Eq[T]` is available. Ordinary
import and shadowing rules apply to the additional standard library names;
conflicting imports may need explicit qualification.

Existing standard library `!=` methods can remain. Their removal is a
separate API change, especially for explicit member calls and view types.
There is no new syntax, backend primitive, or SAST format requirement.

## Related work

| Language | Treatment of inequality |
| --- | --- |
| [Kotlin](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) | `a != b` expands to `!(a == b)`. Equality is customized through `equals`, with no separate inequality implementation. |
| [Scala](https://www.scala-lang.org/api/current/scala/Any.html) | The universal `Any.!=` method is final and negates `==`; equality is normally customized through `equals`. |
| [Rust](https://doc.rust-lang.org/stable/std/cmp/trait.PartialEq.html) | `PartialEq` requires `eq` and provides a default `ne` that negates it. An override is permitted, with a contract requiring complementary results. |
| [Swift](https://developer.apple.com/documentation/swift/equatable) | `Equatable` supplies a default `!=` that negates `==`; its [implementation](https://github.com/swiftlang/swift/blob/main/stdlib/public/core/Equatable.swift) is marked `@_transparent`. Custom inequality implementations are expected to agree with equality. |
| [Haskell](https://www.haskell.org/onlinereport/haskell2010/haskellch6.html) | `Eq` gives `==` and `/=` mutually defined defaults. Defining either supplies the other; defining both replaces both defaults, and defining neither causes recursion. |
| [Python](https://docs.python.org/3/reference/datamodel.html#object.__ne__) | The default `__ne__` delegates to `__eq__` and negates its result, with special handling for `NotImplemented`. Classes can override inequality independently. |
| [C++](https://eel.is/c++draft/over.match.oper#10) | Operator resolution can rewrite `!=` through a suitable Boolean `operator==`. Explicit `operator!=` overloads remain supported; rewriting does not declare a missing member. |
| [C#](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/operators/operator-overloading#overloadable-operators) | Manual equality-operator overloading requires declaring both `==` and `!=`, rather than supplying the missing implementation. |
