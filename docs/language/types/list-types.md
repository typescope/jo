# List Types

Jo standard library implements List type to make daily programming easy. Lists are immutable with efficient O(1) prepend, append, and concat operations.

## List Literals

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

## List Operations

Lists provide common operations for functional programming:

```jo
val numbers = [1, 2, 3, 4, 5]

// Map - transform each element
val doubled = numbers.map(x => x * 2)

// Filter - select elements matching a predicate
val evens = numbers.filter(x => x % 2 == 0)

// Fold/Reduce - combine elements
val sum = numbers.fold(0, (acc, x) => acc + x)

// Prepend - add element to front (O(1))
val withZero = 0 :: numbers

// Append - add element to end (O(1))
val withSix = numbers :+ 6

// Concat - combine lists (O(1))
val combined = [1, 2] ++ [3, 4]
```

## Pattern Matching

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

## List Comprehensions

Lists can be constructed using for expressions:

```jo
// Generate list with for loop
val squares =
  for x in [1, 2, 3, 4, 5] do
    x * x

// With filtering
val evenSquares =
  for x in [1, 2, 3, 4, 5] if x % 2 == 0 do
    x * x
```

## Implementation

Lists in Jo are implemented as an algebraic data type:

```jo
union List[T] = Cons(head: T, tail: List[T]) | Nil
```

This representation enables:
- Structural sharing for efficiency
- Pattern matching for decomposition
- Functional operations like map, filter, fold
- O(1) prepend operation

## See Also

- [Pattern Matching](../patterns/index.md) - For destructuring lists
