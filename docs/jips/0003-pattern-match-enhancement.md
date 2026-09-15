---
author: Fengyun Liu
status: Draft
created: 2026-09-12
title: Pattern match enhancement
---

# JIP 0003 — Pattern match enhancement

<JipMeta />

Pattern matching is one of the most powerful features of Jo. This proposal
extends pattern matching with a deconstruction protocol. The protocol lets the
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
  result[0] = x  // Box Int into Any.
  result[1] = y
  true
```

The call site in `first` translates schematically to:

```jo
def first(p: Point): Int =
  val result = Array.create[Any](2)
  if Point$impl(p, result) then
    val x = cast[Int](result[0])  // Unbox Any to Int.
    val y = cast[Int](result[1])
    x
  else
    abort("Unhandled match")  // Unreachable: the extractor always returns true.
```

The two coordinates already reside in `p`. Nevertheless, the current lowering
allocates an `Array[Any]`, calls the generated extractor to copy both coordinates
into it, and reads the bindings back. On a backend with native integers, the
`Int` values are boxed when stored in `Any` and unboxed when retrieved.
A direct field read, `p.x`, needs none of this intermediate storage.

## Proposal Overview

This proposal consists of three parts:

1. **Deconstruction protocol.** `@deconstruct` enables positional deconstruction
   of an output of class type. The components are the fields named by the class
   constructor's parameters. Elaboration turns the sub-patterns into a member
   pattern.
2. **Class desugaring.** Classes with class parameters synthesize an annotated
   pattern exposing the original object.
3. **Compiler translation.** Pattern definitions become functions with typed
   results. Translation eliminates all patterns into calls, assignments, and
   control flow.

The deconstruction protocol is user-visible. Class desugaring participates in
the protocol automatically, while the generated functions and return
representations are compiler implementation details.

The proposal separates surface syntax from semantics. Semantically, a
deconstruction is a [member pattern](#semantics-member-patterns): it selects
members of a value by name and matches each against a sub-pattern. On the
surface, positional deconstruction is the only form in this proposal, and its
member names come from the class constructor. Named deconstruction elaborates to
the same member patterns, and it is delayed as a
[future extension](#future-extension-named-deconstruction).

## Semantics: member patterns

A member pattern is an internal, elaborated pattern:

```text
MemberPattern([(member1, pat1), ..., (memberN, patN)])
```

It matches a value by selecting the listed members of that value and matching
each member against the corresponding sub-pattern. The member names are
distinct, and there is at least one of them. The type of each sub-pattern is
the type of the selected member, with the type arguments of the value's type
substituted. In this proposal, member patterns only arise from positional
deconstruction, so every selected member is a field.

### Evaluation order

The sub-patterns are tested from left to right, stopping at the first failure,
as in other patterns.

The language does not specify when member fields are read. An implementation
may read all fields before testing any sub-pattern, interleave reads with tests,
or skip reads after a sub-pattern fails. Reading a field has no side effects, so
the difference is observable only if a sub-pattern mutates a `var` field of the
object being matched. Programs must not depend on it.

### Exhaustiveness and reachability

A member pattern is checked like an applied pattern whose arguments are the
selected members. For exhaustivity and reachability checking, the specification
follows the intuitive pattern matching semantics, assuming member fields are
not mutated during the match. We do not specify the algorithm here to reserve
room for flexible implementation.

The rules for irrefutable pattern definitions do not change. An irrefutable
pattern definition should be exhaustive for its declared input type. If
exhaustiveness checking finds an incomplete definition without `Partial`, the
compiler issues a warning. An irrefutable pattern that fails at run time aborts.

## Surface syntax: positional deconstruction

A pattern opts into deconstruction with `@deconstruct`:

```jo
@deconstruct
pattern Point(p: Point): Point = case p
```

A pattern definition annotated with `@deconstruct` must have exactly one output.
At use sites, the output is deconstructed into its components, which are
matched positionally:

```jo
case Point pat1 pat2 => ...
```

Elaboration resolves the component names on the output type and produces a
member pattern on the output:

```text
ApplyPattern(Point, MemberPattern([(x, pat1), (y, pat2)]))
```

Binding names are arbitrary: `Point a b` reads the fields `x` and `y`.

The name reflects the rule for components. A deconstruction mirrors a
construction: `Point(3, 4)` and `case Point x y` take the same components in the
same order.

### Components

The output type of a `@deconstruct` pattern must be a class type, possibly
applied to type arguments, as in `Box[Point]`. Union types, interface types, and
type parameters are errors.

The output type is the type of the pattern's output parameter, which may differ
from the type being matched. The components come from the output type:

```jo
class Box[T](value: T)

@deconstruct
pattern Content(p: Point): Box[Point] = case box then p = box.value

match box
case Content x y => ...  // Matches a Box[Point], reads the fields x and y of its Point.
```

Here the scrutinee has type `Box[Point]`, while the components are the fields
`x` and `y` of the output type `Point`.

A class has exactly one constructor. The components are determined by it:

- The number of components is the number of constructor parameters.
- The component names are the constructor parameter names, in declaration order.
- For each constructor parameter, the class must have a field with the same
  name. Methods, including parameterless methods and members supplied through
  views, do not qualify.

Classes whose constructor has no parameters are unsupported: the class
constructor must have at least one parameter.

Each field must be accessible at the pattern definition, under ordinary
member-access rules. As with an ordinary pattern that reads fields in its body,
the pattern author decides what to expose.

These rules depend only on the declared output type, so they are checked at the
`@deconstruct` pattern definition, not where the pattern is applied. The use
site checks the [arity](#arity), and checks each sub-pattern against the type of
the corresponding field, with the class type arguments substituted.

A class with class parameters satisfies these rules automatically, because class
parameters desugar to a constructor and fields of the same names, and class
parameters are always accessible. A class with an explicit constructor
participates in the protocol by declaring fields named after the constructor
parameters:

```jo
class Temperature
  val celsius: Float

  def Temperature(celsius: Float): Temperature =
    this.celsius = celsius

@deconstruct
pattern Temp(t: Temperature): Temperature = case t

match temperature
case Temp c => ...  // Reads the field celsius.
```

A class whose fields are named differently from its constructor parameters does
not qualify:

```jo
class Rectangle
  val w: Int
  val h: Int

  def Rectangle(width: Int, height: Int): Rectangle =
    this.w = width
    this.h = height

@deconstruct
pattern Rect(r: Rectangle): Rectangle = case r  // Error: Rectangle has no field width.
```

### Arity

The number of sub-patterns must match the number of components.

`@deconstruct` requires deconstruction and disallows matching its output as a single value:

```jo
case Point x y => ...  // Matches the two components.
case Point p => ...    // Error: two component patterns required.
```

For an annotated one-component pattern `Box`, `case Box x` matches the single
field, never the output as a single value. Deconstruction applies once at the annotated
boundary and does not recursively deconstruct components. Ordinary patterns
with no outputs remain supported and are unrelated to classes without
components.

### Refutable deconstruction

Both irrefutable and refutable patterns may be annotated. A refutable
deconstructing pattern names a refinement once and deconstructs the refined
value in the same step:

```jo
@deconstruct
pattern PositivePoint(p: Point): Partial[Point] = case p if p.x > 0 && p.y > 0

match p
case PositivePoint x y => ...
case Point x y => ...
```

Without it, the condition is either repeated as a guard at every use site, as
in `case Point x y if x > 0 && y > 0`, or written as an ordinary pattern whose
output is deconstructed in a second step. Whether an extractor can fail is
independent of how its output is deconstructed. Restricting the annotation to
irrefutable patterns would couple the two properties without gain, because the
translation handles the refutable case through `Option[T]`.

## Class desugaring

A class with class parameters supplies the deconstruction protocol
automatically:

```jo
class Point(x: Int, y: Int)

// Desugars to (showing the pattern-related parts):
class Point
  val x: Int
  val y: Int

  def Point(x: Int, y: Int): Point =
    this.x = x
    this.y = y

@deconstruct
pattern Point(p: Point): Point = case p
```

Previously, the synthesized pattern exposed each class parameter separately:

```jo
pattern Point(x: Int, y: Int): Point =
  case p then x = p.x, y = p.y
```

The new pattern exposes the original object as its single output.
`@deconstruct` preserves positional matching at call sites such as
`case Point x y`. No members are added to the class.

A class with an empty class parameter list, such as `class Unit()`, has no
components. Its synthesized pattern is not annotated with `@deconstruct`.

For a generic class, the synthesized pattern takes the class type parameters:

```jo
class Pair[A, B](first: A, second: B)

// Synthesizes:
@deconstruct
pattern Pair[A, B](p: Pair[A, B]): Pair[A, B] = case p
```

A user-defined pattern with the same name as the class still replaces the
synthesized pattern.

## Pattern translation

::: info Compilation Internals
Pattern translation is an implementation detail. It does not affect compatibility, thanks to
the SAST standard intermediate format and whole-program compilation.

It is documented here to illustrate how the performance improvement is actually implemented.
The concrete details can change without notice.
:::

Pattern translation operates on the elaborated patterns.
It eliminates pattern definitions and their uses: definitions become
functions, and elaborated matches, including member patterns, become calls,
assignments, and control flow.

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

- With no outputs, `Bool` reports the match result directly.
- For single output: irrefutable patterns return the value directly, while refutable patterns return `Option[T]`.
- For multiple outputs: irrefutable patterns return `Array[Any]`, while refutable patterns return `Array[Any] | None`.

The asymmetry between `Option[T]` and `Array[Any] | None` comes from union types.
Each branch of a union must be a class. `Array[Any]` and `None` are both classes,
so `Array[Any] | None` is valid. An arbitrary output type `T` may be an interface
or a type parameter, so `T | None` is not valid in general.

Irrefutable pattern functions have no failure value. If an irrefutable pattern
fails at run time, the program aborts.

::: info Language runtime optimization
We expect highly-optimized language runtimes can effectively optimize away the
allocation of containers for multiple outputs and refutable single output,
e.g., based on inlining and escape analysis.
:::

Irrefutable deconstructing patterns do not allocate any container in the
translation:

```jo
@deconstruct
pattern Point(p: Point): Point = case p

// Translates to
def Point$impl(p: Point): Point = p
```

The call site in `first` from the motivation translates schematically to:

```jo
def first(p: Point): Int =
  val result = Point$impl(p)
  val x = result.x
  val y = result.y
  x
```

Generic classes translate the same way, with type arguments passed to the
pattern function:

```jo
@deconstruct
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
  val a = result.first
  val b = result.second
  a + b
```

A refutable deconstructing pattern has a single output, so its function returns
`Option[T]`. On success it allocates one `Some` to wrap the output, and the call
site reads the fields from the wrapped value. This replaces the `Array[Any]` of
the previous lowering and avoids boxing the components, but it is not free of
allocation:

```jo
@deconstruct
pattern PositivePoint(p: Point): Partial[Point] = case p if p.x > 0 && p.y > 0

// Translates schematically to
def PositivePoint$impl(p: Point): Option[Point] =
  if p.x > 0 && p.y > 0 then Some(p) else None
```

Removing this allocation is left to the runtime, as described above.

## Alternatives considered

**Specialize synthesized class patterns.** The compiler could recognize the
patterns synthesized for classes and read class parameters directly, with no
user-visible protocol. Rejected because it would give synthesized patterns
behavior that no user-written pattern definition can express, while patterns form
a name universe populated only by pattern definitions.

**Treat every single-output pattern as deconstructing.** This would avoid the
annotation. Rejected because it is ambiguous with ordinary single-output
patterns: `case Name n` could mean either binding the output as a single value or matching
its first component. `@deconstruct` makes the intent explicit at the definition.

**Other annotation names.** `@product` suggests tuple-like projections, which
this proposal does not use. `@member` names the elaborated member pattern, but
reads like a class member and says nothing about positional matching.
`@positional` does not distinguish the annotation from ordinary patterns, whose
outputs are also positional. `@deconstruct` states that the use site takes the
output apart, and that it does so by mirroring the constructor.

**Positional projection methods `_1`, `_2`, ...** Classes would synthesize
projection methods, and any type defining them could be matched positionally,
including interfaces. Rejected because positional matching does not scale and is
brittle. A positional API decoupled from the constructor can silently drift from
it, and hand-written projections on types without a constructor lead to code
that is hard to understand. Tying components to the constructor keeps matching the mirror
image of construction: `Point(3, 4)` and `case Point x y` change together, and
the compiler reports both. Complex cases are better served by named
deconstruction, which is more stable as code evolves.

**Allow methods as components.** Parameterless methods could serve as
components alongside fields. Rejected to keep the simple case simple. Methods
may have side effects, context parameter requirements, or type parameters, and
each would need its own rule. Fields have none of these concerns.

## Compatibility

The following breaking changes are intentional:

- The SAST format gains a member pattern node.
- The pattern synthesized for a class with class parameters changes signature.
  `pattern Point(x: Int, y: Int): Point` becomes
  `@deconstruct pattern Point(p: Point): Point`. Call sites such as
  `case Point x y` keep their meaning.

These costs are accepted as part of adopting the deconstruction protocol.

## Future extension: named deconstruction

::: info Not part of this proposal
This section is not normative, and its details are left to a future proposal.
It is included to show that the deconstruction protocol does not block the
extension, and that the protocol anticipates it.
:::

Named deconstruction selects members of the output by name, through the same
`@deconstruct` pattern that enables positional deconstruction:

```jo
case Point(.x is Pos, .y is Pos) => ...
```

The leading `.` selects a member, as in member adapters, and `is` matches the
member against a sub-pattern, as in `is` expressions. When a component only
binds a variable of the same name, `is subpattern` may be omitted: `.x` is
shorthand for `.x is x`.

```jo
case Point(.x is Positive & x) => ...  // Selects only x. Does not read y.
case Point(.y is b, .x is a) => ...    // Tests b, then a.
case Point(.x is 0, .y) => ...         // Tests x, then binds y.
```

Named deconstruction requires parentheses. Positional and named sub-patterns
cannot be mixed in one application. Selected members may be fields or
parameterless methods, and unmentioned members are not read.

Both forms elaborate to member patterns, so the extension adds surface syntax
without changing the semantics:

```jo
case Point x y => ...               // Positional, names from the constructor.
case Point(.x is x, .y is y) => ... // Named, the same member pattern.
```

Named deconstruction goes through the explicit annotation for readability.
A `@deconstruct` pattern establishes a common pattern vocabulary for use sites,
so every deconstruction names its head, including in nested positions:

```jo
if opt is Some(Point(.x is Pos)) then ...
```

A standalone form such as `{ .x is Pos }: Point` was considered. It needs no
pattern definition, so it composes with any type, including interfaces. It is
not adopted because a nested `Some({ .x is Pos })` does not say what is being
deconstructed. The cost is that types without a synthesized pattern, such as
interfaces, need a user-written `@deconstruct` pattern. The future proposal will
decide which output types and definition-site checks apply to named
deconstruction.

The form `Point(x = pat)` is deliberately avoided. Inside patterns, `then x = e`
assigns to the name on the left, while a member selection reads from it. In
calls, `f(x = e)` names a parameter of `f`, not a member of its result.

## Related documentation

- [Pattern definitions](../language/definitions/pattern-definitions.md)
- [Pattern matching semantics](../language/patterns/semantics.md)
- [Class definitions](../language/definitions/class-definitions.md)
- [Design principles](../language/design-principles.md)
