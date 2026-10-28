---
author: Fengyun Liu
status: Proposed
created: 2026-10-28
title: Default not-equals through Eq
---

# JIP 0009 — Default not-equals through Eq

<JipMeta />

## Summary

Add a global `!=` function to the standard library:

```jo
def (a: T) != [T](b: T)(auto eq: Eq[T] with [[T].==]): Bool = !eq.equal(a, b)
```

A class's own `!=` method takes precedence, following the existing rules for
infix operators. When the global function is used, ordinary auto resolution
selects an `Eq[T]`, synthesizing it from `T.==` by default. The compiler
optimizes a call whose equality instance is the compiler-generated adapter
`(a, b) => a == b` into `!(a == b)`.

## Motivation

Classes commonly define `==` and repeat its negation as `!=`. The standard
library does this for primitive types and for `List`, `Option`, `Result`, and
`Set`. A class that defines only `==` can participate in equality-based
collections but still cannot use the corresponding infix `!=` expression.

A global function expresses this default once, using Jo's existing `Eq`
interface and member-first lookup. Optimizing the synthesized adapter removes
the extra function call, equality adapter, and interface dispatch for
common cases.

## Proposal

### Global definition

The function is defined in namespace `jo`. Lookup, type inference, and auto
resolution follow the existing rules. A class's own `!=` method takes
precedence over the global function as usual.

### Custom equality instances

The global function negates the selected `Eq[T]`, which can differ from a
direct call to the class's `==` method:

```jo
class Key(value: Int)
  def ==(other: Key): Bool = this.value == other.value

def example: Unit =
  auto byLastDigit: Eq[Key] = (a, b) => a.value % 10 == b.value % 10
  val a = new Key(1)
  val b = new Key(11)

  assert(!(a == b), "the member compares the complete values")
  assert(!(a != b), "the global function uses byLastDigit")
```

Both comparisons above return `false`. The two expressions use different
equality operations. This is a consequence of honoring contextual `Eq`
instances, and the optimization must preserve it. A class's own `!=` method
continues to control its infix inequality independently of the global default.

### Compiler optimization

When a call to the global `jo.!=` receives the compiler-synthesized equality
adapter `(x, y) => x == y`, inline the function and adapter to produce:

```jo
!(a == b)
```

This removes the wrapper call and adapter overhead. The unoptimized library
function has the same semantics, so correctness does not depend on inlining.

Calls using custom equality instances or a class's own `!=`
retain their ordinary behavior.

## Justification

The proposal follows
[explicitness over implicitness](../language/design-principles.md#_4-explicitness-over-implicitness):
the programmer still supplies equality through a method or an `Eq` instance.
The global function provides its negation, while existing member definitions
remain authoritative.

We avoid adding global operators to standard library.
[JIP 0008](./0008-drop-top-level-int-operators.md) removed monomorphic `Int`
operator fallbacks.
However, we believe adding `!=` is justified given its universality for all types and wide usage.

## Compatibility

Existing member implementations and successfully resolved operator calls
retain their behavior. Code that previously failed because `!=` was missing
can now use the global fallback. Ordinary import and shadowing rules apply to
the additional standard library name; conflicting imports may need explicit
qualification.

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

Rust and Swift provide the closest library precedent: an equality abstraction
supplies a default inequality operation while allowing explicit definitions.
Jo uses an auto `Eq[T]` argument and its existing global-operator fallback,
then removes the synthesized adapter at the typed call site.

