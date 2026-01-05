# Basic Types

Jo provides primitive types that form the foundation of the type system.

## Primitive Types

### Bool
Boolean values representing true or false.

```jo
val flag: Bool = true
val isReady: Bool = false
```

### Int
Integer values for numeric computations.

```jo
val count: Int = 42
val negative: Int = -17
```

### Byte
8-bit integer values.

```jo
val b: Byte = 127
```

### Char
Single character values.

```jo
val letter: Char = 'a'
val digit: Char = '9'
```

### Float
Floating-point numbers for decimal values.

```jo
val pi: Float = 3.14159
val temperature: Float = 98.6
```

### String
Text values.

```jo
val message: String = "Hello, Jo!"
val name: String = "Alice"
```

## Built-in Algebraic Types

Jo's standard library provides commonly used algebraic types:

### Option
Represents an optional value that may or may not be present.

```jo
union Option[T] = Some(value: T) | None

val present: Option[Int] = Some(42)
val absent: Option[Int] = None
```

### Either
Represents a value that can be one of two types, typically used for error handling.

```jo
union Either[L, R] = Left(value: L) | Right(value: R)

val success: Either[String, Int] = Right(42)
val failure: Either[String, Int] = Left("Error occurred")
```

## See Also

- [Union Types](union-types.md) - For creating custom algebraic data types
- [Type Aliases](type-aliases.md) - For creating meaningful names for basic types
