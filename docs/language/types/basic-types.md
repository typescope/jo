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

A signed 32-bit integer, the default integer type; the range is `[-2^31, 2^31 - 1]`.
Arithmetic (`+`, `-`, `*`, unary `-`) that overflows the range is undefined
behavior — a wrapped result is still a bug, so it is left unspecified. Bitwise
operations (`<<`, `>>`, `&`, `|`, `^`) respect the 32-bit two's-complement
representation (for example, `1 << 31` is the most-negative value).

```jo
val count: Int = 42
val negative: Int = -17
```

### Long

A signed 64-bit integer; the range is `[-2^63, 2^63 - 1]`. As with `Int`,
arithmetic overflow is undefined behavior while bitwise operations respect the
64-bit two's-complement representation.

```jo
val big: Long = 9223372036854775807
val small: Int = 42
val widened: Long = small   // Int widens to Long automatically
```

### Byte

An unsigned 8-bit value in the range `[0, 255]`. Byte is a storage type with no
arithmetic of its own; convert to `Int` with `toInt` to compute.

```jo
val b: Byte = 200
```

### Char

Unicode code points representing single characters. Supports the full Unicode range (U+0000 to U+10FFFF), excluding surrogate code points (U+D800 to U+DFFF).

```jo
val letter: Char = 'a'
val digit: Char = '9'

// Emojis and characters beyond U+FFFF
val smiley: Char = '😀'     // U+1F600 = 128512
val rocket: Char = 0x1F680  // U+1F680 = 128640

// Boundaries
val min: Char = 0           // U+0000
val max: Char = 0x10FFFF    // U+10FFFF (highest valid Unicode code point)

// Invalid: surrogate code points are rejected
// val invalid: Char = 0xD800  // Error: surrogate code point
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

### Unit

The type `Unit` represents normal completion of an action.
It helps maintain the invariant that every function returns a value.

If a function declares `Unit` as return type, the compiler will automatically
insert a `Unit` value if the function body does not conform to `Unit`.

If a function is intended to do nothing with an empty body, `pass` can be used
to simply return a `Unit` value.

The concrete representation of Unit is determined by the target platform.

## Types in Standard Library

Jo's standard library provides commonly used data types:

### Option

Represents an optional value that may or may not be present.

```jo
union Option[T] = Some(value: T) | None

val present: Option[Int] = Some(42)
val absent: Option[Int] = None
```

### List

Immutable sequential collection with efficient O(1) prepend, append, and concat operations.

```jo
val numbers = [1, 2, 3, 4, 5]
val names = ["Alice", "Bob", "Charlie"]

// Common operations
val doubled = numbers.map(x => x * 2)
val evens = numbers.select(x => x % 2 == 0)
val sum = numbers.fold(0, (acc, x) => acc + x)
```

### Result

Represents a computation that either succeeds with a value or fails with an error.

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)

val success: Result[Int, String] = Ok(42)
val failure: Result[Int, String] = Err("not found")

match result
case Ok(v)  => println("Got: " + v)
case Err(e) => println("Error: " + e)
```

### Map

Immutable key-value mapping based on binary search tree. Requires an `Ord` instance for the key type.

```jo
val ages = Map("Alice" ~ 30, "Bob" ~ 25)
val aliceAge = ages["Alice"]  // 30
```

### Set

Immutable collection of unique elements based on binary search tree. Requires an `Ord` instance for the element type.

```jo
val numbers = Set(1, 2, 3, 4, 5)
val hasTwo = numbers.contains(2)  // true
```

## See Also

- [Union Types](union-types.md) - For creating custom algebraic data types
- [Named Types](named-types.md) - Class types, interface types, type aliases
