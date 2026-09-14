---
author: Fengyun Liu
status: Draft
created: 2026-09-12
title: Pattern match enhancement
---

# JIP 0003 — Pattern match enhancement

<JipMeta />

Pattern matching is one of the most powerful features of Jo. This proposal
extends pattern matching with a product pattern protocol. The protocol lets the
compiler translate pattern matches efficiently, and lets users write pattern
definitions that follow the same protocol to avoid unnecessary allocation.

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

1. **Product pattern protocol.** `@product` enables positional component
   matching. Elaboration inserts a typed product-pattern node for the
   sub-patterns.
2. **Class desugaring.** Classes with class parameters synthesize positional
   projection methods and an annotated pattern exposing the original object.
3. **Compiler translation.** Pattern definitions become functions with typed
   results. Translation eliminates all patterns into calls, assignments, and
   control flow.

The product pattern protocol is user-visible. Class desugaring plays into the protocol
automatically, while the generated functions and return representations are
compiler implementation details.

Matching components by member name is a natural companion to the protocol, but
it is delayed as a [future extension](#future-extension-named-component-patterns).

## Elaboration: product pattern protocol

A pattern opts into product expansion with `@product`:

```jo
@product
pattern Point(p: Point): Point = case p
```

A pattern definition annotated with `@product` must have exactly one output.
The annotation identifies the pattern's single output as a product whose
components are matched positionally:

```jo
case Point pat1 pat2 => ...
```

Elaboration resolves the projections on the successful output type and inserts
an internal product-pattern node:

```text
ApplyPattern(Point, ProductPattern([(_1, pat1), (_2, pat2)]))
```

This applies to both irrefutable and refutable patterns. Binding names are
arbitrary: `Point a b` reads `_1` and `_2`.

### Projections

A projection must be a field or a parameterless method:

- It must have no method type parameters.
- It must have no context parameter requirements, explicit or inferred.

Each projection must resolve to an accessible member on the successful output
type under ordinary member-access rules. Projections supplied through views
qualify under the same rules as direct members. These restrictions are
intentionally strict initially.

Each sub-pattern is checked against the corresponding field's type or method's
result type.

By convention, projections are **stable**: reading a projection has no
observable side effects, and reading it again during a match yields the same
value. This is the same convention that other languages with extensible pattern
matching adopt. The compiler does not check it. Exhaustiveness and reachability
checking assume it, so a match that is statically exhaustive can fail at run
time if a projection breaks the convention.

### Arity

Arity is determined by the consecutive projection methods
`_1`, `_2`, and so on, starting at `_1` and stopping at the first gap:

| Projection methods present | Product arity |
|---|---|
| `_1`, `_2` | 2 |
| `_1`, `_3` | 1 |
| `_2` without `_1` | Positional matching unsupported |

Positional matching requires at least `_1`. Zero-component products are
unsupported. The number of sub-patterns must match the product arity.

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

### Evaluation order

When the extractor succeeds, the sub-patterns are tested from left to right,
stopping at the first failure, as in other patterns.

The language does not specify when projections are read. An implementation may
read all projections before testing any sub-pattern, interleave reads with
tests, or skip reads after a sub-pattern fails. Programs must not depend on the
order or number of projection reads. This follows from the stability convention
above.

### Exhaustiveness and reachability

A product pattern is checked like an applied pattern whose arguments are its
components. For exhaustivity and reachability checking, the specification
follows the intuitive pattern matching semantics, assuming stable projections.
We do not specify the algorithm here to reserve room for flexible
implementation.

The rules for irrefutable pattern definitions do not change. An irrefutable
pattern definition should be exhaustive for its declared input type. If
exhaustiveness checking finds an incomplete definition without `Partial`, the
compiler issues a warning. An irrefutable pattern that fails at run time aborts.

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

For a generic class, the projections use the class type parameters, and the
synthesized pattern takes the same type parameters:

```jo
class Pair[A, B](first: A, second: B)

// Desugars to (showing the product-related additions):
class Pair[A, B](first: A, second: B)
  def _1: A = this.first
  def _2: B = this.second

@product
pattern Pair[A, B](p: Pair[A, B]): Pair[A, B] = case p
```

Projection methods are synthesized unconditionally in class-parameter declaration order,
including when a user-defined pattern replaces the generated pattern.
This keeps projection types and order fixed by the class declaration.

Synthesized projections participate in the existing
[member uniqueness check](../language/definitions/class-definitions.md#member-uniqueness).
Conflicts with direct members, synthetic view forwarders, or concrete methods
inherited through views therefore produce errors under that rule. Synthesis
does not skip or replace conflicting members.

## Pattern translation

::: info Compilation Internals
Pattern translation is compiler internals, which does not affect compatibility thanks to
the SAST standard intermediate format and whole-program compilation.

It is documented here to illustrate how the performance improvement is actually implemented.
The concrete details can change without notice.
:::

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

Irrefutable pattern functions have no failure value. If an irrefutable pattern
fails at run time, its function aborts.

::: info Language runtime optimization
We expect highly-optimized language runtimes can effectively optimize away the
allocation of containers for multiple outputs and refutable single output,
e.g., based on inlining and escape analysis.
:::

Irrefutable product patterns now do not need to allocate containers in the
translation:

```jo
@product
pattern Point(p: Point): Point = case p

// Translates to
def Point$impl(p: Point): Point = p
```

The call site in `first` from the motivation translates schematically to:

```jo
def first(p: Point): Int =
  val result = Point$impl(p)
  val x = result._1
  val y = result._2
  x
```

Generic classes translate the same way, with type arguments passed to the
pattern function:

```jo
@product
pattern Pair[A, B](p: Pair[A, B]): Pair[A, B] = case p

// Translates to
def Pair$impl[A, B](p: Pair[A, B]): Pair[A, B] = p

// A call site
def sum(pair: Pair[Int, Int]): Int =
  match pair
  case Pair a b => a + b

// Translates schematically to
def sum(pair: Pair[Int, Int]): Int =
  val result = Pair$impl[Int, Int](pair)
  val a = result._1
  val b = result._2
  a + b
```

## Alternatives considered

**Specialize synthesized class patterns.** The compiler could recognize the
patterns synthesized for classes and read class parameters directly, with no
user-visible protocol. Rejected because only compiler-generated patterns would
benefit: user-defined patterns could not play into the protocol and would keep
paying for the output container.

**Treat every single-output pattern as a product.** This would avoid the
annotation. Rejected because it is ambiguous with ordinary single-output
patterns: `case Name n` could mean either binding the whole output or matching
its first component. `@product` makes the intent explicit at the definition.

**Name positional projections after class parameters.** Positional matching
needs an order on the members of the output type. Class-parameter names carry
no position once a pattern is decoupled from the class, as in
`@product pattern Position(p: Point): Box[Point]`, and classes without class
parameters have no parameter list at all. The `_1`, `_2` convention supplies the
order uniformly and lets any class play into the protocol by defining projection
methods.

Defining `_1`, `_2` by hand in a class without class parameters may be obscure,
and is arguably poor style. But positional matching for classes with class
parameters needs some order-carrying convention, and this proposal finds no
alternative to such a convention.

## Compatibility

The following breaking changes are intentional:

- Synthesized positional projection names `_1, _2, ...` can conflict with existing class
  members.
- The SAST format gains a product pattern node.

The compatibility costs above are accepted as part of adopting the product protocol.

## Future extension: named component patterns

::: info Not part of this proposal
This section is not normative, and its details are left to a future proposal.
It is included to show that the product protocol does not block the extension,
and that the protocol anticipates it.
:::

A named component pattern matches members of the scrutinee by name:

```jo
{ .member is subpattern, ... }
```

It is a standalone pattern rather than an argument form of an applied pattern,
so it can appear wherever a pattern can:

```jo
match shape
case { .x, .y }: Point => ...

val { .x, .y } = point

if point is Some({ .x, .y }) then ...
```

Members are resolved on the static type of the scrutinee at that position. In
`{ .x, .y }: Point`, the type test refines the scrutinee to `Point` first. This
form extends the type pattern from `name: type` to `{ ... }: type`. In
`Some({ .x, .y })`, the members are resolved on the component type of `Some`.
Because member selection does not depend on a pattern definition, no `@product`
annotation is needed: any scrutinee type with qualifying members can be matched.

The two features stay separate. A pattern marked with `@product` only accepts
positional component patterns. Nothing is lost: to match members by name, users
write a typed pattern instead of applying the product pattern:

```jo
case Point x y => ...          // Positional, through @product.
case { .x, .y }: Point => ...  // Named, through a typed pattern.
```

The leading `.` selects a member, as in member adapters, and `is` matches the
member against a sub-pattern, as in `is` expressions. When a component only
binds a variable of the same name, `is subpattern` may be omitted: `.x` is
shorthand for `.x is x`.

```jo
case { .x is Positive & x } => ...  // Selects only x. Does not read y.
case { .y is b, .x is a } => ...    // Tests b, then a.
case { .x is 0, .y } => ...         // Tests x, then binds y.
```

The rules for projections carry over: selected members must be accessible
fields or parameterless methods without method type parameters or context
requirements, and are expected to be stable. Unmentioned members are not read.

The form `Point(x = pat)` is deliberately avoided. Inside patterns, `then x = e`
assigns to the name on the left, while a member selection reads from it. In
calls, `f(x = e)` names a parameter of `f`, not a member of its result.

## Related documentation

- [Pattern definitions](../language/definitions/pattern-definitions.md)
- [Pattern matching semantics](../language/patterns/semantics.md)
- [Class definitions](../language/definitions/class-definitions.md)
- [Design principles](../language/design-principles.md)
