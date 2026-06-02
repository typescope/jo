# Named Types

Four kinds of definition introduce named types:

| Definition | Kind | Nominal? | Transparent? |
|---|---|---|---|
| `class C` | class type | yes | no |
| `interface I` | interface type | yes | no |
| `type A = T` | type alias | — | yes |
| type parameter `T` | type variable | yes | no |

## Class Types

A class definition introduces a new, distinct nominal type. Two class types are equal if
and only if they refer to the same definition with equal type arguments:

```jo
class Point[T](x: T, y: T)

type A = Point[Int]
type B = Point[Int]   // A and B are the same type
type C = Point[Float] // Different from A and B
```

### Subtyping via Views

A class type `C` becomes a subtype of an interface type `I` through a view declaration.
Both forms create `C <: I`:

**Direct view** — the class implements the interface methods itself:

```jo
class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger
end

val l: Logger = new FileLogger("/tmp/log")  // OK: FileLogger <: Logger
```

**Delegate view** — the class forwards abstract interface methods to a held object:

```jo
class Service(logger: Logger)
  view Logger = logger
end

val l: Logger = new Service(someLogger)  // OK: Service <: Logger
```

### Class Types in Union Types

Class types are the only permitted branch types in union types. A union `C1 | C2 | ...`
is a type whose values are instances of exactly one of the listed classes:

```jo
class Success(value: Int)
class Failure(error: String)

type Result = Success | Failure

def process(): Result =
  if ok then Success(42) else Failure("oops")
```

See [Union Types](union-types.md) for well-formedness rules and exhaustiveness checking.

### Generic Class Types are Invariant

Type parameters on generic classes are invariant — neither covariant nor contravariant.
Each instantiation is a distinct type with no subtype relationship to other instantiations:

```jo
class Box[T](value: T)

val intBox: Box[Int] = new Box(42)
val anyBox: Box[Any] = intBox  // Error: Box[Int] is not a subtype of Box[Any]
```

## Interface Types

An interface definition introduces a named interface type. Interface types participate
in subtyping as supertypes: a class is a subtype of an interface only by declaring a
view. The compiler verifies that all abstract methods are implemented.

Interface types cannot appear as branches of union types (see
[Union Types](union-types.md)).

## Type Aliases

A type alias `type A = T` introduces a transparent name for `T`. The alias and its
underlying type are indistinguishable — at type checking, at runtime, and across
modules:

```jo
type UserId = Int

val id: UserId = 42
val n: Int = id   // OK: UserId and Int are the same type
```

Aliases are **substitutable** in both directions:

```jo
type EmailAddress = String
type UserName = String

def sendEmail(to: EmailAddress): Unit = ...

val name: UserName = "Alice"
sendEmail(name)  // OK: UserName = String = EmailAddress
```

Aliases have **no runtime representation** and leave no trace after compilation. For a
distinct type that is not interchangeable with its underlying type, use a class:

```jo
class Temperature(value: Float)
class Distance(value: Float)

val t = Temperature(98.6)
val d: Distance = t  // Error: different types
```

## Type Parameters

A type parameter `T` declared on a definition introduces a nominal type variable scoped
to that definition. It is only a subtype of itself — two distinct type parameters `T`
and `U` are unrelated unless constrained. Type parameters are resolved by substitution
at instantiation sites and are erased before code generation.

## See Also

- [Class Definitions](../definitions/class-definitions.md) - Class syntax, fields, methods, views
- [Interface Definitions](../definitions/interface-definitions.md) - Interface syntax and abstract methods
- [Type Definitions](../definitions/type-definitions.md) - Type alias syntax
- [Union Types](union-types.md) - Sum types over class branches
