# Union Types

## Overview

Union types enable a value to be one of several possible class types, with the ability to distinguish between alternatives at runtime through pattern matching.
In addition, a union type may contain at most one lambda type as a branch.

Unlike traditional sum types or tagged unions found in functional languages, Jo's union types are based on **class identity** rather than explicit tags or constructors.

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

// Union with a lambda branch
type Handler = String | (Int => Int)

// Union as parameter type
def describe(shape: Circle | Rectangle): String =
  match shape
    case c: Circle => "Circle with radius " + c.r
    case r: Rectangle => "Rectangle " + r.w + "x" + r.h
  end
```

### Type Alias for Unions

Union types can be named using type aliases:

```jo
type Shape = Circle | Rectangle | Triangle

def area(s: Shape): Float =
  match s
    case c: Circle => 3.14 * c.r * c.r
    case r: Rectangle => r.w * r.h
    case t: Triangle => 0.5 * t.base * t.height
  end
```

### Pattern Matching on Unions

Union types are deconstructed using pattern matching with type patterns:

```jo
def processResult(result: Success | Failure): Unit =
  match result
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

### Lambda Branches

A lambda branch is matched with a type pattern on the lambda type:

```jo
type Lazy = Int | (() => Int)

def force(v: Lazy): Int =
  match v
    case n: Int => n
    case f: (() => Int) => f()
  end
end

force(42)          // 42
force(() => 42)    // 42
```

At runtime, the type test only checks whether the value is a lambda. Parameter
and result types of lambdas cannot be checked at runtime. Therefore, a lambda
type pattern is only allowed when the scrutinee type is a union type whose
lambda branch conforms to the pattern type, or when the scrutinee type already
conforms to the pattern type:

```jo
def run(h: String | (Int => Int)): Int =
  match h
    case f: (String => Int) => 1   // Error: Int => Int does not conform to String => Int
    case _ => 0
  end
end
```

## Type Checking

### Well-Formed Union Types

A union type `T1 | T2 | ... | Tn` is well-formed if:

1. **Each branch is a class type, lambda type or union type**: `Ti` must be:

    - A class type: `C[T1, ..., Tm]` where `C` is a class definition
    - A lambda type: `(A1, ..., Am) => R`
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

7. **No multiple lambda branches**. After flattening, a union type contains at most one lambda type.

    ```jo
    // ❌ Invalid - multiple lambda types
    type BadHandler = (Int => Int) | (String => Int)   // Compile error

    type Handler = String | (Int => Int)
    type BadHandler2 = Handler | (Bool => Bool)        // Compile error

    // ✓ Valid - single lambda type
    type Lazy = Int | (() => Int)                      // OK
    ```


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

### Exhaustiveness Checking

Pattern matching on union types must be exhaustive:

```jo
type Result = Success | Warning | Failure

def process(r: Result): String =
  match r
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
  match r
    case s: Success => "ok"
    case f: Failure => "error"
    case x: Success => "redundant"  // Warning: Redundant case (Success already covered)
  end
end
```

## Design Decisions

### Why Restrict to Class Types?

Apart from a single lambda branch, union types only allow class types (not interfaces or type parameters).

A value can implement multiple interfaces simultaneously, so there is no way to
guarantee that interface branches are mutually exclusive. Pattern matching would
be ambiguous: if an object implements both `Logger` and `Formatter`, which branch
of `Logger | Formatter` matches? This ambiguity cannot be resolved reliably across
platforms.

To dispatch on interface identity, wrap the implementations in distinct classes:

```jo
class ConsoleLoggerImpl(logger: Logger)
  view Logger = logger
end

class FileLoggerImpl(logger: Logger)
  view Logger = logger
end

type LoggerUnion = ConsoleLoggerImpl | FileLoggerImpl
```

### Why At Most One Lambda Branch?

At runtime, it is only possible to tell whether a value is a lambda. The
parameter and result types of a lambda are not available. Two lambda branches
such as `(Int => Int) | (String => Int)` cannot be distinguished at runtime, so
pattern matching would be ambiguous. This is the same reason as the restriction
to one numeric branch.

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

Use pattern matching instead:

```jo
val width = match s
  case c: Circle => c.r * 2
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
