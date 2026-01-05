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

```jo
union Option[T] = Some(value: T) | None

val present: Option[Int] = Some(42)
val absent: Option[Int] = None
```

### Either

```jo
union Either[L, R] = Left(value: L) | Right(value: R)

val success: Either[String, Int] = Right(42)
val failure: Either[String, Int] = Left("Error occurred")
```

### List

Lists are immutable with efficient O(1) prepend, append, and concat operations.

Lists are created using square bracket syntax:

```jo
// Empty list with explicit type
val empty: List[Int] = []

// List with elements
val numbers = [1, 2, 3]

// List of strings
val names = ["Alice", "Bob", "Charlie"]

// Nested lists
val matrix = [[1, 2], [3, 4], [5, 6]]
```

Lists provide common operations for functional programming:

```jo
val numbers = [1, 2, 3, 4, 5]

// Map - transform each element
val doubled = numbers.map(x => x * 2)

// Filter - select elements matching a predicate
val evens = numbers.select(x => x % 2 == 0)

// Fold/Reduce - combine elements
val sum = numbers.fold(0, (acc, x) => acc + x)

// Prepend - add element to front (O(1))
val withZero = [0, ..numbers]

// Append - add element to end (O(1))
val withSix = [..numbers, 6]

// Concat - combine lists (O(1))
val combined = [..l1, ..l2]
```

Lists support pattern matching for decomposition:

```jo
match numbers
case [] =>
  println "Empty list"
case [head, ..tail] =>
  println ("First element: " + head)
  println ("Rest: " + tail)
end

// Match specific lengths
match numbers
case [x] => println "Singleton"
case [x, y] => println "Pair"
case [x, y, z] => println "Triple"
case _ => println "Longer list"
end
```

Lists can be used in for expressions as it exposes an `iterator`:

```jo
// Generate list with for loop
for x in [1, 2, 3, 4, 5] do
  println (x * x)

for x in [1, 2, 3, 4, 5] if x % 2 == 0 do
  println (x * x)
```


## See Also

- [Union Types](union-types.md) - For creating custom algebraic data types
- [Type Aliases](type-aliases.md) - For creating meaningful names for basic types
