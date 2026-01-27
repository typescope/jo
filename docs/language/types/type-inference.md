# Type Inference

Jo performs local type inference, reducing the need for explicit type annotations while maintaining type safety.

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

## Lambda Parameter Inference

Lambda parameter types can be either inferred from usage or from expected type:

```jo
// `x` inferred to have type `Int`
val double = x => x * 2

// `x` inferred to have type `String`
val log: String => Unit = x => println(x)
```

## Type Parameter Inference

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

## Context Parameter Inference

Context parameter requirements are inferred from function calls:

```jo
// Effects inferred from body
def process(path: String) =       // Inferred: receives IO.open, IO.stdout
  val content = File.read(path)   // Uses 'IO.open'
  println(content)                // Uses 'IO.stdout'
```

## When Annotations Are Required

Some situations require explicit type annotations:

### 1. Recursive Functions

Return type must be explicit for recursion:

```jo
// ❌ Error - cannot infer recursive type
def factorial(n: Int) =
  if n <= 1 then 1 else n * factorial(n - 1)

// ✓ OK - explicit return type
def factorial(n: Int): Int =
  if n <= 1 then 1 else n * factorial(n - 1)
```

### 2. Ambiguous Contexts

Explicit annotation clarifies intent:

```jo
// Ambiguous - need explicit annotation
val value = if condition then 42 else "hello"

// Clear intent with annotation
val value: String = if condition then "42" else "hello"
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


## See Also

- [Lambda Types](lambda-types.md) - For lambda type inference
