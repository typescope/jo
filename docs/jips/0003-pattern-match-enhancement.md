---
author: Fengyun Liu
status: Draft
created: 2026-09-12
title: Pattern match enhancement
---

# JIP 0003 — Pattern match enhancement

<JipMeta />

Pattern matching is one of the most powerful features of Jo. This proposal
extends pattern matching with a product pattern protocol to make it
possible to both optimize translation of pattern matches, as well as for users
to micro-optimize pattern definitions by following the protocol.

## Motivation

Pattern matching often inspects data that already exists. The current pattern
implementation introduces a separate output container even when a pattern only reads
fields from an existing object.

Consider a point and a function that reads its first coordinate:

```jo
class Point(x: Int, y: Int)

def first(p: Point): Int =
  match p
  case Point x y => x
```

The class currently generates this pattern definition:

```jo
pattern Point(x: Int, y: Int): Point =
  case p then x = p.x, y = p.y
```

The pattern definition translates schematically to:

```jo
def Point$impl(p: Point, result: Array[Any]): Bool =
  val x = p.x
  val y = p.y
  result.set(0, x)  // Box Int into Any.
  result.set(1, y)
  true
```

The call site in `first` translates schematically to:

```jo
def first(p: Point): Int =
  val result = Array.create[Any](2)
  if Point$impl(p, result) then
    val x = cast[Int](result.get(0))  // Unbox Any to Int.
    val y = cast[Int](result.get(1))
    x
  else
    abort("Unhandled match")
```

The two coordinates already reside in `p`. Nevertheless, the current lowering
allocates an `Array[Any]`, calls the generated extractor to copy both coordinates
into it, and reads the bindings back. On a backend with native integers, the
`Int` values are boxed when stored in `Any` and unboxed when retrieved.
A direct field read, `p.x`, needs none of this intermediate storage.

## Proposal Overview

This proposal consists of three parts:

1. **Product pattern protocol.** `@product` enables component matching.
   Elaboration inserts a typed product-pattern node for the sub-patterns.
2. **Class desugaring.** Classes with class parameters synthesize positional
   projection methods and an annotated pattern exposing the original object.
3. **Compiler translation.** Pattern definitions become functions with typed
   results. Translation eliminates all patterns into calls, assignments, and
   control flow.

The product pattern protocol is user-visible. Class desugaring plays into the protocol
automatically, while the generated functions and return representations are
compiler implementation details.

## Elaboration: product pattern protocol

A pattern opts into product expansion with `@product`:

```jo
@product
pattern Point(p: Point): Point = case p
```

A pattern definition annotated with `@product` must have exactly one output.
The annotation identifies the pattern's single output as a product that supports
positional or named component matching:

```jo
case Point pat1 pat2 => ...
case Point(x = pat1, y = pat2) => ...
```

Elaboration resolves the projections on the successful output type and inserts
the same kind of internal product-pattern node for either form:

```text
// Positional:
ApplyPattern(Point, ProductPattern([(_1, pat1), (_2, pat2)]))

// Named:
ApplyPattern(Point, ProductPattern([(x, pat1), (y, pat2)]))
```

This applies to both irrefutable and refutable patterns. Positional binding
names are arbitrary: `Point a b` reads `_1` and `_2`. In the named form, `x` and
`y` select members, while `pat1` and `pat2` determine the bindings and tests.

On success, first read all selected components in written order. Then test
their sub-patterns from left to right, stopping at the first failure. Component
reads complete before any sub-pattern is tested. Effects in a sub-pattern cannot
change which values were already read for later components.

### Positional product patterns

Positional projections must be parameterless methods or a field.

- They must have no context parameter requirements.
- They must have no method type parameters.

Projection methods supplied through views qualify under the same rules as direct
members, subject to ordinary member resolution. These restrictions are
intentionally strict initially.

Arity is determined by the consecutive methods
`_1`, `_2`, and so on, starting at `_1` and stopping at the first gap:

| Projection methods present | Product arity |
|---|---|
| `_1`, `_2` | 2 |
| `_1`, `_3` | 1 |
| `_2` without `_1` | Positional matching unsupported |

Positional matching requires
at least `_1`; zero-component positional products are unsupported. Its positional
argument count must match the product arity. Each positional sub-pattern is
checked against the corresponding method's result type. Named-only matching
requires no positional projections on the output type.

`@product` requires component matching and disallows matching its whole output.
There is no fallback to ordinary output binding:

```jo
case Point x y => ...  // Matches the two components.
case Point p => ...    // Error: two component patterns required.
```

For an annotated one-component pattern `Box`, `case Box x` matches `_1`, never
the whole output. Decomposition applies once at the annotated boundary and does
not recursively flatten product-valued components. Ordinary patterns with no
outputs remain supported and are unrelated to zero-component products.


### Named product patterns

Named component matching uses `Pattern(member = subpattern, ...)` and obeys
the following rules:

- The applied pattern must be annotated with `@product`. Named arguments on an
  unannotated pattern are an error.
- At least one named selection is required. Empty selections are errors;
  `Pattern()` is not a valid component match for an annotated pattern.
- All arguments must be named. Mixing positional and named arguments is an error.
- Each member name may occur at most once. Duplicate selections are errors,
  regardless of the sub-patterns or bindings used.
- Each selected name must resolve to an accessible member on the successful
  output type under ordinary member-access rules. A missing or inaccessible
  member is an error.
- The selected member must be a field or a parameterless method. Methods with
  parameter lists, method type parameters, or context parameter requirements
  (explicit or inferred) do not qualify. Members supplied through views qualify
  under the same rules as direct members.
- Each sub-pattern is checked against the selected field's type or method's
  result type.

Named matching selects a subset of members. Unmentioned members are neither
read nor matched. It does not use positional arity and requires no correspondence
between a named member and `_1`, `_2`, etc. Selected members are read in written
order before their sub-patterns are tested, as specified above.

```jo
case Point(x = Positive & x) => ...  // Selects only x. Does not read y.
case Point(y = b, x = a) => ...     // Reads y, then x; tests b, then a.
case Point(x = a, b) => ...         // Error: mixed named and positional arguments.
case Point(x = a, x = b) => ...     // Error: duplicate member selection.
case Point(z = a) => ...            // Error: Point has no member z.
```

### Exhaustiveness and reachability

An irrefutable pattern definition must be exhaustive for its declared input type.
If exhaustiveness checking finds an incomplete definition without `Partial`, the
compiler issues an error instead of the current warning. The author must make the
definition exhaustive or declare `Partial[T]`. This applies regardless of the
number of outputs and ensures that irrefutable implementations need no failure
representation.

For coverage checking, `ProductPattern` is a special apply pattern: its input is
the successful output type of the enclosing extractor, and its arguments are the
selected component sub-patterns. Its projections always supply component values
when they return normally; only the component sub-patterns can reject those
values. An irrefutable extractor does not by itself make the complete application
irrefutable.

The checker represents a product shape by its instantiated output type and
ordered list of resolved projection members. Each argument space is obtained by
checking the corresponding sub-pattern against the projection's result type.
For the same shape, use the ordinary apply-pattern rules for component-wise
space subtraction and disjointness. A product space is empty if any component
space is empty. It covers the entire output type if every selected component
covers its corresponding type. Unselected members impose no restriction.

The following rules apply to the enclosing annotated pattern application:

- The input type test retains its existing coverage behavior. Coverage of the
  extractor's input type does not cover other branches of a wider scrutinee type.
- For an irrefutable extractor, the nested product space determines component
  coverage. Applications of the same extractor, with the same instantiated types
  and product shape, can collectively cover that shape using the ordinary
  apply-pattern rules.
- For a `Partial` extractor, retain the existing conservative partial-pattern
  treatment. Covering every possible successful output does not establish that
  the extractor succeeds for every input.
- Guards, refutable nested extractors, Boolean pattern composition, and bindings
  retain their existing coverage rules. Product expansion does not make an
  unknown guard or a partial component test exhaustive.

Named and positional syntax have the same coverage meaning when they resolve to
the same ordered members. For example, `Point(a, b)` and
`Point(_1 = a, _2 = b)` have the same product shape. The checker does not infer
that `x` and `_1` are equivalent by inspecting their implementations. Different
member selections or orders are compared conservatively using output-type
coverage; component-wise subtraction requires the same shape. In particular, a
named subset with irrefutable sub-patterns still covers the whole output type.

```jo
class Flag(value: Bool)

match flag: Flag
case Flag(value = true) => ...
case Flag(value = false) => ...  // Exhaustive: both values of the component.

match flag: Flag
case Flag(value = true) => ...  // Non-exhaustive: false is missing.

match flag: Flag
case Flag(value = _) => ...
case Flag(value = true) => ...  // Unreachable: the preceding case covers Flag.
```

Exhaustiveness and reachability diagnostics use the existing sequential process:
start with the scrutinee's type space, diagnose a case disjoint from the remaining
space as unreachable, and subtract each case's coverage. A nonempty remainder
produces the existing non-exhaustive-match diagnostic. In a pattern definition
without `Partial`, incomplete coverage is an error as specified below. These
rules apply in all existing pattern contexts, including pattern value definitions
and `is` expressions where coverage information is used.

As with ordinary apply patterns, this analysis reasons about declared types and
component spaces, not arbitrary method bodies. It does not prove correlations
between components or stability of repeated projection calls. Coverage results
do not authorize caching, reordering, or omitting projection calls or case tests;
translation must preserve the specified evaluation order and effects.

## Class desugaring

A class with class parameters supplies the product protocol automatically:

```jo
class Point(x: Int, y: Int)

// Desugars to (showing the product-related additions):
class Point(x: Int, y: Int)
  def _1: Int = this.x
  def _2: Int = this.y

@product
pattern Point(p: Point): Point = case p
```

Previously, the synthesized pattern exposed each class parameter separately:

```jo
pattern Point(x: Int, y: Int): Point =
  case p then x = p.x, y = p.y
```

The new pattern exposes the original object as its single output. `@product`
preserves component matching at call sites such as `case Point x y`.

Projection methods are synthesized unconditionally in class-parameter declaration order,
including when a user-defined pattern replaces the generated pattern.
Additional class-body fields do not participate. An existing member with the
same name conflicts and produces a compile-time error, even if its type or
implementation is identical. This keeps projection types and order fixed by
the class declaration.

Synthesized projections participate in the existing
[member uniqueness check](../language/definitions/class-definitions.md#member-uniqueness).
Conflicts with direct members, synthetic view forwarders, or concrete methods
inherited through views therefore produce errors under that rule; synthesis
does not skip or replace conflicting members.

## Pattern translation

Pattern translation operates on the elaborated patterns.
It eliminates pattern definitions and their uses: definitions become
functions, and elaborated matches become calls, assignments, and control flow.

Pattern definitions now follow the scheme below:

| Pattern outputs | Irrefutable returns | Refutable returns |
|---|---|---|
| No outputs | `Bool` | `Bool` |
| One output `T` | `T` | `Option[T]` |
| Multiple outputs `A`, `B`, `C` | `Array[Any]` | `Array[Any] \| None` |

For example:

```jo
pattern Pat(x: A, y: B, z: C): Partial[T] = ...

// Translates to:
def Pat$impl(scrut: T): Array[Any] | None = ...
```

- With no outputs, `Bool` reports match result directly.
- For single output: irrefutable patterns return the value directly, while refutable patterns return `Option[T]`.
- For multiple outputs: irrefutable patterns return `Array[Any]`, while refutable patterns return `Array[Any] | None`.

## Compatibility

The following breaking changes are intentional:

- Incomplete pattern definitions without `Partial` become compile-time errors.
- Synthesized positional projection names can conflict with existing class
  members, as specified above.
- A product's consecutive positional projections define its public pattern
  arity. Adding a projection or filling a gap can change that arity and invalidate
  existing positional matches.

Existing class component matching syntax is preserved when no projection-name
conflict occurs. The compatibility costs above are accepted as part of adopting
the product protocol.

## Related documentation

- [Pattern definitions](../language/definitions/pattern-definitions.md)
- [Pattern matching semantics](../language/patterns/semantics.md)
- [Class definitions](../language/definitions/class-definitions.md)
- [Design principles](../language/design-principles.md)
