# Union Types

## Overview

Union types enable a value to be one of several possible class types, with the ability to distinguish between alternatives at runtime through pattern matching.

Unlike traditional sum types or tagged unions found in functional languages, Jo's union types are based on **class identity** rather than explicit tags or constructors.

## Motivation

Union types solve the problem of representing values that can be one of several specific types:

```jo
// Represent a result that can be either success or failure
class Success(value: Int)

class Failure(error: String)

def divide(x: Int, y: Int): Success | Failure =
  if y == 0 then new Failure("Division by zero")
  else new Success(x / y)

def processResult(result: Success | Failure): String =
  result match
    case s: Success => "Result: " + s.value
    case f: Failure => "Error: " + f.error
  end
end
```

## Syntax

### Union Type Syntax

```
union_type = type {"|" type}
```

Union types are written using the `|` operator between type expressions:

```jo
// Simple union of two class types
type Shape = Circle | Rectangle

// Union of multiple class types
type Result = Success | Warning | Error

// Nested unions (flattened automatically)
type Number = Int | Float
type Value = Number | String  // Equivalent to: Int | Float | String

// Union as return type
def parse(s: String): Int | Error =
  if isValid(s) then parseToInt(s)
  else new Error("Invalid input")

// Union as parameter type
def describe(shape: Circle | Rectangle): String =
  shape match
    case c: Circle => "Circle with radius " + c.r
    case r: Rectangle => "Rectangle " + r.w + "x" + r.h
  end
```

### Type Alias for Unions

Union types can be named using type aliases:

```jo
type Shape = Circle | Rectangle | Triangle

def area(s: Shape): Float =
  s match
    case c: Circle => 3.14 * c.r * c.r
    case r: Rectangle => r.w * r.h
    case t: Triangle => 0.5 * t.base * t.height
  end
```

### Pattern Matching on Unions

Union types are deconstructed using pattern matching with type patterns:

```jo
def processResult(result: Success | Failure): Unit =
  result match
    case s: Success =>
      println("Got value: " + s.value)
    case f: Failure =>
      println("Got error: " + f.error)
  end
end
```

The compiler:

1. Verifies all alternatives are covered (exhaustiveness check)
2. Generates code to test the runtime type against each case
3. Binds the scrutinee to the appropriate type in each branch

## Type Checking

### Well-Formed Union Types

A union type `T1 | T2 | ... | Tn` is well-formed if:

1. **Each branch is a class type or union type**: `Ti` must be:

    - A class type: `C[T1, ..., Tm]` where `C` is a class definition
    - Another union type (which will be flattened)

2. **No type parameters**: Branches cannot contain type parameters:
   ```jo
   // Invalid
   def foo[T](x: T | Int): Unit = ...  // Error: T is a type parameter
   ```

3. **No interface types**: Branches cannot be interface types:
   ```jo
   interface Logger
     def log(msg: String): Unit
   end

   // Invalid
   type LoggerOrInt = Logger | Int  // Error: Logger is an interface type
   ```

4. **All branches statically known**: The complete set of alternatives must be determined at compile time

5. **No duplicate branches**: After normalization, each class type appears at most once:
   ```jo
   // Invalid
   type Foo = Int | String | Int  // Error: Int appears twice
   ```

6. **No multiple numeric branches**. A union type cannot contain multiple numeric types (Int, Byte, Char, Float).

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


!!! note "Rationale for Restrictions"
    - **No type parameters**: Type parameters are resolved at instantiation time, making it impossible to know the complete set of alternatives statically
    - **No interface types**: Interface types don't have class identity and can be implemented by any class, making them open-ended
    - **Statically known branches**: Enables exhaustiveness checking and efficient compilation
    - **No multiple numeric branches**: Enables platform portability and easy interoperability
### Subtyping with Union Types

Union types introduce limited subtyping relationships:

**Each branch is a subtype of the union:**

```jo
class Success(value: Int)
class Failure(error: String)

type Result = Success | Failure

def makeSuccess(): Result = new Success(42)  // Success <: Result
def makeFailure(): Result = new Failure("oops")  // Failure <: Result
```

**Union subsumption:**

```jo
type Small = Int | String
type Large = Int | String | Bool

// Small <: Large (all branches of Small are in Large)

def foo(x: Small): Large = x  // Valid: implicit widening
```

!!!warning "Int is not a subtype of Int | String"

    For platform portability, we impose a constraint in the type system:
    **a numeric type is never a subtype of a union type**.

    For example, while `String` is a subtype of `String | Int`, `Int` is not.
    This is the minimal constraint we impose on the type system to make union
    types work smoothly across target platforms (Python, Ruby, JVM).

    This will not impact ordinary usage, as users may still write:

        val a: Int | String = 5

    The code above works thanks to an automatic adaptation from numeric types to
    union types.


### Type Adaptation and Member Selection

Union types **do not support member selection** directly:

```jo
type Shape = Circle | Rectangle

val s: Shape = new Circle(5)
val r = s.r  // Error: Cannot select member 'r' from union type Shape
```

!!! note "Rationale"
    Different branches may have different members. Use pattern matching to access members:

```jo
val radius = s match
  case c: Circle => c.r
  case r: Rectangle => 0  // Doesn't have radius
end
```

!!! info "No Common Interface Required"
    Unlike sealed interfaces in some languages, union types do not require branches to implement a common interface. Each branch is independent.

### Exhaustiveness Checking

Pattern matching on union types must be exhaustive:

```jo
type Result = Success | Warning | Failure

def process(r: Result): String =
  r match
    case s: Success => "ok"
    case w: Warning => "warning"
    // Error: Missing case for Failure
  end
end
```

The compiler tracks which branches are covered and reports missing cases.

#### Redundant Patterns

```jo
def process(r: Success | Failure): String =
  r match
    case s: Success => "ok"
    case f: Failure => "error"
    case x: Success => "redundant"  // Warning: Redundant case (Success already covered)
  end
end
```

## Design Decisions

### Why Restrict to Class Types Only?

Union types only allow class types (not interfaces or type parameters).

!!! warning "Platform Ambiguity: Interfaces Cannot Guarantee Mutual Exclusiveness"
    A value can implement multiple interfaces simultaneously (on both JVM and JavaScript platforms), making it impossible to guarantee that union branches are mutually exclusive.

    For example, with `Logger | Formatter` where both are interfaces, a single object could implement both interfaces. When pattern matching, which branch should it match? The first? The second? This creates fundamental ambiguity that cannot be resolved reliably.

    **This constraint is essential** — allowing interfaces in unions would impose too much constraint on platform implementations of interfaces. Each platform (JVM, JavaScript, native) handles interfaces differently, and mandating mutual exclusiveness would severely limit implementation flexibility.

!!! tip "Workaround for Interfaces"
    Wrap interface values in classes:

```jo
interface Logger
  def log(msg: String): Unit
end

class ConsoleLoggerImpl(logger: Logger)
  view Logger = logger
end

class FileLoggerImpl(logger: Logger)
  view Logger = logger
end

type LoggerUnion = ConsoleLoggerImpl | FileLoggerImpl
```

### Why No Member Access on Union Types?

Direct member selection is prohibited on union types:

```jo
val s: Circle | Rectangle = ...
val x = s.width  // Error: Cannot access member on union type
```

**Rationale:**

1. **Type safety**: Different branches have different members; which one to access?
2. **Clarity**: Pattern matching makes the branch selection explicit
3. **No implicit consensus**: Unlike interfaces (where all implementors agree on member signatures), union branches are independent

**Alternative considered:** Allow member access if all branches have compatible members. Rejected because:

- Adds complexity to type checking
- Fragile: Adding a new branch without that member breaks existing code
- Implicit coupling between unrelated classes

!!! tip "Use Pattern Matching Instead"

```jo
val width = s match
  case c: Circle => c.r * 2  // Diameter as "width"
  case r: Rectangle => r.w
end
```

### Why Prohibit Type Parameters in Branches?

```jo
// Invalid
def foo[T](x: T | Int): Unit = ...
```

This is prohibited because:

1. **Type erasure**: In generic code, `T` is erased at runtime; no way to perform type tests against unknown type
2. **Unbounded alternatives**: `T` could be instantiated with infinitely many types
3. **Exhaustiveness**: Cannot check if all cases are covered when `T` is unknown
