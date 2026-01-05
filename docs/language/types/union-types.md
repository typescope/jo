# Union Types

Union types represent values that can be one of several alternatives, specified with `|` separating the branches. Each branch must be either a class type or another union type.

## Syntax

```jo
type TypeName = Branch1 | Branch2 | Branch3
```

## Basic Union Types

Union types combine existing class types:

```jo
// Classes for union branches
class Success
class Warning
class Error

// Union type combining classes
type Status = Success | Warning | Error

// Usage
val status: Status = Success
```

## Parameterized Unions

Union branches can be parameterized classes:

```jo
// Parameterized classes
class Data(content: String)
class ErrorMsg(message: String)
class Empty

// Union type with parameterized branches
type Response = Data | ErrorMsg | Empty

// Usage
val response: Response = Data("some content")
```

## Generic Union Types

Union types can be generic:

```jo
class None
class Some[T](value: T)
type Option[T] = None | Some[T]

class Left[L](value: L)
class Right[R](value: R)
type Either[L, R] = Left[L] | Right[R]

// Usage
val maybeInt: Option[Int] = Some(42)
val result: Either[String, Int] = Right(100)
```

## Pattern Matching

Union types work seamlessly with pattern matching:

```jo
val result: Response = Data("some content")

match result
case Data(content) => println ("Success: " + content)
case ErrorMsg(message) => println ("Error: " + message)
case Empty => println "No data"
end
```

## Restrictions on Union Types

### Numeric Type Restriction

A union type cannot contain multiple numeric types (Int, Byte, Char, Float).

```jo
// ❌ Invalid - multiple numeric types
type BadUnion = Int | Float      // Compile error
type BadUnion2 = Char | Byte     // Compile error

// ✓ Valid - single numeric type
type GoodUnion = Float | String  // OK
type GoodUnion2 = Int | List[T]  // OK

// ✓ Use tagged unions instead for multiple numeric variants
union NumericValue =
  IntValue(n: Int) |
  DoubleValue(d: Float)
```

!!!warning "Int is not a subtype of Int | String"

    For platform portability, we impose a constraint in the type system:
    **a numeric type is never a subtype of a union type**.

    For example, while `String` is a subtype of `String | Int`, `Int` is not.
    This is the minimal constraint we impose on the type system to make union
    types work smoothly across target platforms (JavaScript, Ruby, JVM, Native).

    This will not impact ordinary usage, as users may still write:

        val a: Int | String = 5

    The code above works thanks to a built-in adaptation from numeric types to
    union types.

## Union Definitions

Jo provides the `union` keyword as syntactic sugar for defining union types. Union definitions automatically generate the necessary classes, type aliases, constructor functions, and patterns:

```jo
union Option[T] = Some(value: T) | None

// Equivalent to:
class None
class Some[T](value: T)
type Option[T] = None | Some[T]
// Plus auto-generated constructor functions and patterns
```

See [Algebraic Data Types](../definitions/adt.md) for details on union definitions.

## Exhaustiveness Checking

The compiler checks that pattern matching on union types is exhaustive:

```jo
union Status = Success | Warning | Error

def classify(status: Status): String =
  match status
  case Success => "OK"
  case Warning => "Warning"
  case Error => "Error"  // All cases covered - no warning
  end

// Missing case - compiler warning
def incomplete(status: Status): String =
  match status
  case Success => "OK"
  // Warning: non-exhaustive match - missing Warning and Error
  end
```

## See Also

- [Algebraic Data Types](../definitions/adt.md) - For union definitions with the `union` keyword
- [Pattern Matching](../patterns/index.md) - For destructuring union values
