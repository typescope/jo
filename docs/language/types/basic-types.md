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

### Map

Immutable key-value mapping based on binary search tree. Requires an `Ord` instance for the key type.

```jo
val ages = {"Alice" ~ 30, "Bob" ~ 25}
val aliceAge = ages["Alice"]  // 30
```

### Set

Immutable collection of unique elements based on binary search tree. Requires an `Ord` instance for the element type.

```jo
val numbers = {1, 2, 3, 4, 5}
val hasTwo = numbers.contains(2)  // true
```

### Mutable Map

Hash table-based mutable key-value mapping. Requires `Eq` and `Hash` instances for the key type.

```jo
val scores: mutable.Map[String, Int] = {"Alice" ~ 100}
scores["Alice"] = 95
```

### Mutable Set

Hash table-based mutable collection of unique elements. Requires `Eq` and `Hash` instances for the element type.

```jo
val tags: mutable.Set[String] = {"important", "urgent"}
tags.add("completed")
```


## See Also

- [Union Types](union-types.md) - For creating custom algebraic data types
- [Type Aliases](type-aliases.md) - For creating meaningful names for basic types
