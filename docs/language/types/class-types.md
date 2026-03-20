# Class Types

A class definition defines a class type. Class types represent structured values with named fields and methods.

## Class Types and Subtyping

### No Subtyping with Views

A class type is **not** a subtype of the views it implements. Views provide a mechanism for type adaptation, but they do not create subtype relationships.

```jo
interface Logger
  def log(msg: String): Unit
end

class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger
end

// FileLogger is NOT a subtype of Logger
val logger: Logger = fileLogger  // Error: type mismatch
val logger: Logger = fileLogger.Logger  // OK: access view field
```

Type adaptation occurs implicitly when the type context requires it:

```jo
def useLogger(logger: Logger): Unit = ...

val fileLogger = new FileLogger("/tmp/log")
useLogger(fileLogger)  // OK: implicit view adaptation
```

::: info Design Rationale
This design avoids the fragile base class problem and diamond inheritance issues. Classes and their views remain independent, supporting composition over inheritance.
:::
### Generic Class Types are Invariant

Type parameters in generic classes are **invariant**—neither covariant nor contravariant.

```jo
class Box[T](value: T)
  def get: T = value
  def set(newValue: T): Unit = ...
end

// Box[Int] is NOT a subtype of Box[Any]
// Box[Any] is NOT a subtype of Box[Int]
val intBox: Box[Int] = new Box(42)
val anyBox: Box[Any] = intBox  // Error: type mismatch
```

Each instantiation of a generic class creates a distinct type with no subtype relationship.

::: info Design Rationale
Invariance is simpler and safer. Variance annotations on type parameters would add significant complexity to type checking without a big improvement in expressiveness and usability.
:::
### Class Types in Union Types

Class types can be used as alternatives in union types:

```jo
class Success(value: Int)
class Failure(error: String)

union Result = Success | Failure

def process(): Result =
  if condition then
    Success(42)
  else
    Failure("error")

// Pattern matching on union types
match process()
case Success(v) => println("Got: " + v)
case Failure(e) => println("Error: " + e)
```

Union types enable algebraic data types with nominal alternatives, allowing pattern matching and exhaustiveness checking.

## Type Equality

Two class types are equal if and only if:

1. They refer to the same class definition
2. Their type arguments are equal (for generic classes)

```jo
class Point[T](x: T, y: T)

// Equal types
type A = Point[Int]
type B = Point[Int]  // Same as A

// Different types
type C = Point[Float]  // Different from A and B
type D = Point[String]  // Different from A, B, and C
```

## See Also

- [Class Definitions](../definitions/class-definitions.md) - Syntax and semantics of class definitions
- [Union Types](union-types.md) - Union type definitions
