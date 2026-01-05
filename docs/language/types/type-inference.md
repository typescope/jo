# Type Inference

Jo performs sophisticated type inference, reducing the need for explicit type annotations while maintaining type safety.

## Overview

Type inference automatically determines types based on:
- Value initialization
- Function usage
- Control flow
- Pattern matching
- Generic type parameters

## Value Inference

Type is inferred from the initializer:

```jo
// Type inferred as List[Int]
val numbers = [1, 2, 3, 4, 5]

// Type inferred as String
val message = "Hello, World!"

// Type inferred as Option[Int]
val maybeNumber = Some(42)

// Type inferred as Bool
val flag = true
```

## Function Inference

Function types are inferred from usage:

```jo
// Type inferred as Int => Int
val double = x => x * 2

// Type inferred as String => Int
val length = s => s.size

// Type inferred as (Int, Int) => Int
val add = (x, y) => x + y
```

## Generic Type Inference

Type parameters are inferred from arguments:

```jo
def identity[T](x: T): T = x

// T inferred as Int
val num = identity(42)

// T inferred as String
val str = identity("hello")

// T inferred as List[Int]
val list = identity([1, 2, 3])
```

## Pattern Match Inference

Types are refined through pattern matching:

```jo
val value: Int | String = getValue()

match value
case x: Int =>
  // x has type Int here
  val doubled = x * 2
case s: String =>
  // s has type String here
  val len = s.length
end
```

## Control Flow Inference

Types are inferred from control flow:

```jo
// Type inferred as Option[String]
val result = if condition then Some("value") else None

// Type inferred as Int | String
val mixed = if flag then 42 else "hello"

// Type inferred as List[Int]
val filtered = [1, 2, 3, 4, 5].filter(x => x > 2)
```

## Lambda Parameter Inference

Lambda parameter types are inferred from context:

```jo
val numbers = [1, 2, 3, 4, 5]

// x inferred as Int from List[Int]
val doubled = numbers.map(x => x * 2)

// x inferred as Int, return type inferred as Bool
val evens = numbers.filter(x => x % 2 == 0)

// acc and x inferred as Int
val sum = numbers.fold(0, (acc, x) => acc + x)
```

## Return Type Inference

Function return types are inferred from the body:

```jo
// Return type inferred as Int
def square(x: Int) = x * x

// Return type inferred as List[Int]
def range(n: Int) =
  if n <= 0 then []
  else [n, ..range(n - 1)]

// Return type inferred as Option[String]
def findUser(id: Int) =
  if userExists(id) then
    Some(getUserName(id))
  else
    None
```

## Effect Inference

Effect requirements are inferred from function calls:

```jo
// Effects inferred from body
def process(path: String) =
  val content = File.read(path)  // Uses 'open'
  println(content)                // Uses 'IO.stdout'
  // Inferred: String => Unit receives open, IO.stdout
```

## When Annotations Are Required

Some situations require explicit type annotations:

### 1. Recursive Functions

Return type must be explicit for recursion:

```jo
// ❌ Error - cannot infer recursive type
def factorial(n: Int) =
  if n <= 1 then 1
  else n * factorial(n - 1)

// ✓ OK - explicit return type
def factorial(n: Int): Int =
  if n <= 1 then 1
  else n * factorial(n - 1)
```

### 2. Empty Collections

Explicit type needed for empty collections:

```jo
// ❌ Error - cannot infer element type
val empty = []

// ✓ OK - explicit type
val empty: List[Int] = []

// ✓ OK - type inferred from usage
val empty = List[Int]()
```

### 3. Ambiguous Contexts

Explicit annotation clarifies intent:

```jo
// Ambiguous - could be multiple types
val value = if condition then 42 else "hello"
// Inferred as Int | String

// Clear intent with annotation
val value: String = if condition then "42" else "hello"
```

### 4. Public APIs

Explicit types improve readability and documentation:

```jo
// Less clear
def process(data) = data.map(x => x * 2).filter(x => x > 0)

// More clear - good for public APIs
def process(data: List[Int]): List[Int] =
  data.map(x => x * 2).filter(x => x > 0)
```

## Type Inference Algorithm

Jo uses bidirectional type checking:

1. **Inference mode**: Infer type from expression
2. **Checking mode**: Check expression against expected type
3. **Synthesis**: Combine inferred and expected types

This approach enables:

- Local type inference
- Better error messages
- Efficient type checking

## Benefits

### Concise Code

Less boilerplate, more readable:

```jo
// Without inference - verbose
val numbers: List[Int] = [1, 2, 3]
val doubled: List[Int] = numbers.map((x: Int) => x * 2)
val evens: List[Int] = doubled.filter((x: Int) => x % 2 == 0)

// With inference - concise
val numbers = [1, 2, 3]
val doubled = numbers.map(x => x * 2)
val evens = doubled.filter(x => x % 2 == 0)
```

### Type Safety

Inference doesn't compromise safety:

```jo
val numbers = [1, 2, 3]

// Type error caught - string not compatible with Int
// val invalid = numbers.map(x => "not a number")
```

### Refactoring Support

Change types in one place:

```jo
// Change return type here
def getData(): List[String] = ["a", "b", "c"]

// All usage sites automatically get new type
val data = getData()  // Type updates automatically
val count = data.length
```

## See Also

- [Lambda Types](lambda-types.md) - For lambda type inference
- [Effect Types](effect-types.md) - For effect inference
