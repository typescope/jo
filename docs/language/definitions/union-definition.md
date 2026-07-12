# Union Definitions

A `union` definition is a sum type: a type whose values are drawn from a fixed set of
named alternatives (branches), each optionally carrying associated data.

## Syntax

```
union_def = "union" ident [tparams] "=" branch {"|" branch} [methods] ["end"]
branch    = ident [param_section]
methods   = {method_def}
```

## Desugaring

A `union` desugars into a union type alias and a class (or object) per branch.

**Parameterless branch** → object:

```jo
union Color = Red | Green | Blue
// desugars to:
type Color = Red | Green | Blue
object Red;  object Green;  object Blue
```

**Branch with parameters** → class with parameters:

```jo
union Option[T] = Some(value: T) | None
// desugars to:
type Option[T] = Some[T] | None
class Some[T](value: T)   // + factory function + pattern
object None
```

Each branch receives only the type parameters it references in its own parameter list.
Unused type parameters are dropped:

```jo
union Either[A, B] = Left(value: A) | Right(value: B)
// desugars to:
class Left[A](value: A)   // no B
class Right[B](value: B)  // no A
```

## Methods

Methods defined inside the `union` body are attached to the union type via an
[extension type](../types/extension-types.md). `this` refers to the union value.

```jo
union Option[T] = Some(value: T) | None
  def getOrElse(default: T): T =
    match this
      case Some(v) => v
      case None => default
end
```

Desugars to:

```jo
type Option[T] = (Some[T] | None) :+ [Option.getOrElse]

section Option
  def [T](this: Option[T]) getOrElse(default: T): T = ...
end
```

The generated `section Option` merges with any user-defined `section Option` in the same
scope, so factory methods or utilities can be added without conflict:

```jo
union Option[T] = Some(value: T) | None
  def isEmpty: Bool = this is None
end

section Option
  def from[T](value: T): Option[T] = Some(value)  // added alongside union methods
end
```

## Pattern Matching

Each branch generates a pattern used for destructuring in `match` expressions. The
compiler checks exhaustiveness. See [Patterns](../patterns/overview.md).

```jo
def describe(opt: Option[Int]): String =
  match opt
    case Some(v) => "Value: " + v
    case None => "No value"
  end
```

## See Also

- [Patterns](../patterns/overview.md) — pattern matching syntax and exhaustiveness
- [Union Types](../types/union-types.md) — union types in the type system
