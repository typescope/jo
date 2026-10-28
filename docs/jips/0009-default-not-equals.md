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
selects an `Eq[T]`, synthesizing it from `T.==` by default. The type checker
optimizes a call whose equality instance is the compiler-generated adapter
`(a, b) => a == b` into `!(a == b)`

## Motivation

Classes commonly define `==` and repeat its negation as `!=`. The standard
library does this for primitive types and for `List`, `Option`, `Result`, and
`Set`. A class that defines only `==` can participate in equality-based
collections but still cannot use the corresponding infix `!=` expression.

Pairs illustrate the gap: adding component equality allows lists of pairs to
compare for equality, but a separate wrapper is still needed to write
`pair1 != pair2`.

A global function expresses this default once, using Jo's existing `Eq`
interface and member-first lookup. Optimizing the synthesized adapter removes
the extra function call, equality adapter, and interface dispatch from the
common case.

## Proposal

### Global definition and lookup

The new function belongs to namespace `jo` and is available through the usual
standard library imports. Its auto parameter has type `Eq[T]`: a value of
type `T` itself does not provide the `equal(a, b)` operation.

For an infix expression `a != b`:

1. Resolve a callable member `!=` on the receiver using the existing operator
   rules. When present, use that member, including its own auto parameters.
2. Otherwise, resolve the operator in the surrounding scope as today. The
   standard library's global `!=` supplies the default when selected.
3. Infer `T` from the operands and other available type information, then
   resolve `Eq[T]` using the ordinary auto rules. A local instance can take
   precedence over the `[[T].==]` candidate.
4. Return the negation of the selected instance's `equal(a, b)` result.

An inaccessible member, an invalid argument, or a failed auto search on a
selected member remains an error. Such errors do not trigger a retry through
the global function.

The fallback requires an `Eq[T]` for the inferred operand type. It supplies
neither universal equality nor a default identity comparison. A type without
an applicable instance or `==` method still receives an auto-resolution error.

This adds an ordinary global function, rather than a synthesized member to
every class. Explicit member selection such as `a.!=(b)` and view requirements
for a member named `!=` retain their existing rules.

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

### Type checker optimization

The optimization applies after operator lookup, type inference, and auto
resolution. Recognize a fully applied call to the exact standard library
function `jo.!=`, with an equality argument synthesized from the `[[T].==]`
member candidate. Auto resolution already constructs an eta-expanded lambda
for this candidate and adapts it to the single-method `Eq[T]` interface.

For the schematic call:

```jo
notEquals(a, b, synthesizedEq((x, y) => x == y))
```

produce the equivalent typed expression:

```jo
!(a == b)
```

The transformation substitutes the operands into the already-resolved
equality call. It preserves that call's receiver, method symbol, type
arguments, and resolved auto arguments. It must not perform a new lookup of
`==` or a new auto search in the caller's scope.

Preserve the original application's evaluation order and evaluate each
operand once. Retain observable evaluation of any captured auto arguments,
using temporary bindings when needed. Preserve effects, result adaptation,
and source positions for diagnostics.

Recognition should use the compiler-generated adapter's structure or
provenance, including the interface adaptation. A synthetic-lambda flag alone
does not establish that an arbitrary equality argument is this adapter.
An explicitly supplied equality value, a custom auto instance, a class's own
`!=`, or another global function named `!=` keeps its ordinary behavior.

The optimization is optional for correctness: without it, the library
function and adapter implement the same semantics. Backends consume the
resulting ordinary equality call and negation without new primitives.

## Justification

The proposal follows
[explicitness over implicitness](../language/design-principles.md#_4-explicitness-over-implicitness):
the programmer still supplies equality through a method or an `Eq` instance.
The global function provides its negation, while existing member definitions
remain authoritative.

[JIP 0008](./0008-drop-top-level-int-operators.md) removed monomorphic `Int`
operator fallbacks because they guessed `Int` when an operand's type was
unknown. This proposal introduces a polymorphic function that works uniformly
for types with an `Eq` instance. It must not choose `Int`, or another concrete
type, merely because `!=` appears in an untyped lambda. When the program does
not determine `T`, a type annotation is still required.

An unconditional rewrite of every `a != b` into `!(a == b)` would give a
stronger complement guarantee, but would bypass both custom `Eq` instances
and existing `!=` methods. This proposal preserves those extension points.

## Compatibility

Existing member implementations and successfully resolved operator calls
retain their behavior. Code that previously failed because `!=` was missing
can now use the global fallback. Ordinary import and shadowing rules apply to
the additional standard library name; conflicting imports may need explicit
qualification.

Existing standard library `!=` methods can remain. Their removal is a
separate API change, especially for explicit member calls and view types.
There is no new syntax, backend primitive, or SAST format requirement. Code
using the fallback requires the updated standard library; the optimization
requires the updated compiler.

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

## Implementation

Proposed; not yet implemented.
