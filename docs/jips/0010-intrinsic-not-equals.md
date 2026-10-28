---
author: Fengyun Liu
status: Proposed
created: 2026-10-28
title: Intrinsic not-equals
---

# JIP 0010 — Intrinsic not-equals

<JipMeta />

## Summary

Add one global intrinsic to the standard library:

```jo
@intrinsic
def (a: T) != [T](b: T): Bool = ...
```

After type checking a call, the compiler intrinsifies it into `!(a == b)`.
The comparison must use a `==` member defined on the instantiated `T` and
return `Bool`. A missing or invalid equality method is a compile-time error.

This provides the usual negation of equality without requiring each class
to repeat a `!=` wrapper.

## Motivation

The standard library repeatedly defines inequality as the negation of a
type's equality method:

```jo
def !=(other: T): Bool = !(this == other)
```

The useful default is this direct relationship between the two operations.

[JIP 0009](./0009-default-equality-operators.md) proposed global `==` and `!=`
functions taking an auto `Eq[T]`, followed by an optimization that removed
the synthesized equality adapter. It was rejected for feature creep,
indirect semantics, and compiler complexity. This proposal keeps the
original goal and implements it directly through intrinsic specialization.

## Proposal

### Definition and lookup

The intrinsic is defined in namespace `jo` and follows the usual import and
operator lookup rules. A class's own `!=` method takes precedence as usual.

When the global intrinsic is selected, equality must be available as a
member of `T`. An ambient `Eq[T]` does not supply that member and cannot make
the call valid.

```jo
class Key(value: Int)
  def ==(other: Key): Bool = this.value == other.value

def example: Bool =
  new Key(1) != new Key(2)  // becomes !(new Key(1) == new Key(2))
```

### Compiler intrinsification

Once the intrinsic call's operands have been typed and `T` instantiated,
resolve and check the `==` member on `T`. Use that comparison and Boolean
negation to replace the call:

```jo
!(a == b)
```

The equality call must accept the right operand and return `Bool`. Its
visibility, auto parameters, and effects are checked as for an ordinary
member call. Failures are reported at the original `!=` expression.

Any auto parameters required by the equality method itself retain their normal
resolution and evaluation.

### Generic code

The intrinsic does not enable comparison of an unconstrained type parameter
merely because an auto `Eq[T]` is present:

```jo
def different[T](a: T, b: T)(auto eq: Eq[T]): Bool =
  a != b  // error: T has no == member
```

Generic code using an equality instance should state that operation
explicitly, for example:

```jo
def different[T](a: T, b: T)(auto eq: Eq[T]): Bool =
  !eq.equal(a, b)
```

The intrinsic checks equality against the statically available type. It
does not postpone an invalid generic body until concrete callers happen to
supply a type with `==`.

## Justification

### Explicitness and semantic clarity

The intrinsic directly negates the class's equality method. A contextual
`Eq[T]` cannot silently change which equality operation it uses. Ordinary
`==` keeps its existing meaning and lookup rules.

Equality remains an explicit design decision for each type. A type with no
`==` member receives an error, rather than a universal identity comparison
or a synthesized structural comparison.

### Engineering simplicity

The compiler specializes one intrinsic at a typed call site. It needs no
equality dictionary, synthesized adapter, or recognition pass to recover a
direct call from those abstractions. This requires no general-purpose
specialization framework.

The design follows the principles of
[explicitness over implicitness](../language/design-principles.md#_4-explicitness-over-implicitness)
and [engineering simplicity](../language/design-principles.md).

## Compatibility

Existing code is unaffected. Classes with `==` but no `!=` gain infix
inequality. Existing `!=` methods retain precedence.

## Related work

[Kotlin](https://kotlinlang.org/spec/expressions.html#value-equality-expressions)
defines `a != b` as `!(a == b)`.
[C++](https://eel.is/c++draft/over.match.oper#10) can rewrite inequality
through an equality operator while retaining explicit inequality overloads.
These provide precedents for deriving inequality directly from equality.

The broader survey in
[JIP 0009](./0009-default-equality-operators.md#related-work) compares these
approaches with default methods and equality protocols in other languages.

## Implementation

Proposed; not yet implemented.
