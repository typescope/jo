# Pattern Forms

Pattern forms are the basic building blocks of patterns in Jo.

## Literal Patterns

**Syntax:** `42`, `true`, `'a'`, `"hello"`

Matches values equal to the literal. Uses structural equality for comparison.

```jo
match x
case 0 => "zero"
case 1 => "one"
case 42 => "the answer"
end

match status
case "success" => handleSuccess()
case "error" => handleError()
end

match flag
case true => "yes"
case false => "no"
end

match char
case 'a' => "letter a"
case '0' => "digit zero"
end
```

## Variable Patterns

**Syntax:** `x`, `result`, `_`

Binds the scrutinee to the given identifier. Always succeeds. The identifier `_` is a wildcard that matches anything without binding.

```jo
match x
case y => y + 1  // binds x to y
end

match value
case _ => 0      // matches but doesn't bind
end

// Useful for ignoring parts of structures
match pair
case (x, _) => x  // Only bind first element
end
```

## Type Patterns

**Syntax:** `x: Type`

Binds the scrutinee to identifier `x` with the constraint that it must have type `Type`. Performs a type test and binding.

```jo
match value
case x: Int => x + 1
case s: String => s.length
case b: Bool => if b then 1 else 0
end

// Type patterns with union types
match result
case data: Data => processData(data)
case error: ErrorMsg => logError(error)
end
```

### Type Refinement

Type patterns refine the type in the matched branch:

```jo
val value: Int | String = getValue()

match value
case n: Int =>
  // n has type Int here
  val doubled = n * 2
case s: String =>
  // s has type String here
  val len = s.length
end
```

## Bind Patterns

**Syntax:** `x @ pattern`

Matches the scrutinee against `pattern` and additionally binds the entire scrutinee to `x`.

```jo
match list
case xs @ Cons(_, _) =>
  // xs is bound to the whole list
  // and the pattern confirms it's non-empty
  xs.length
end

match tree
case node @ Branch(left, value, right) =>
  // node is bound to the whole tree
  // left, value, right are also bound
  processNode(node)
end
```

### Use Cases

Bind patterns are useful when you need:

1. The whole value
2. Parts of the value
3. Both

```jo
match config
case c @ Config(host, port, timeout) =>
  // Can use c for the whole config
  validateConfig(c)
  // Can use individual fields
  println("Host: " + host)
end
```

## Apply Patterns

**Syntax:** `Constructor(pattern₁, ..., patternₙ)`

Matches values of algebraic data types. Extracts constructor arguments and matches them against nested patterns.

```jo
match option
case Some(x) => x
case None => 0
end

match result
case Ok(value) => value
case Err(message) => -1
end

match tree
case Leaf(value) => [value]
case Branch(left, value, right) =>
  traverse(left) ++ [value] ++ traverse(right)
end
```

### Nested Apply Patterns

Patterns can be nested arbitrarily:

```jo
match nested
case Some(Some(x)) => x
case Some(None) => 0
case None => -1
end

match tree
case Branch(Leaf(x), _, Leaf(y)) =>
  x + y
case Branch(left, value, right) =>
  process(left, value, right)
case Leaf(x) =>
  x
end
```

## Sequence Patterns

**Syntax:** `[pattern₁, ..., patternₙ]`

Matches sequences (lists, arrays) of a specific length, matching each element against the corresponding pattern.

```jo
match list
case [] => "empty"
case [x] => "singleton: " + x
case [x, y] => "pair: " + x + ", " + y
case [x, y, z] => "triple"
end
```

### List Spread Patterns

Match list head and tail:

```jo
match list
case [head, ..tail] =>
  // head is the first element
  // tail is the rest of the list
  head + sum(tail)
end

match list
case [first, second, ..rest] =>
  // first and second are bound
  // rest is the remaining list
  process(first, second, rest)
end
```

## Guard Patterns

**Syntax:** `pattern if condition`

A guard adds a boolean condition to a pattern. The pattern matches only if both the pattern matches and the condition evaluates to `true`. The condition can reference variables bound by the pattern.

```jo
match x
case n if n > 0 => "positive"
case n if n < 0 => "negative"
case _ => "zero"
end

match user
case User(name, age) if age >= 18 =>
  "Adult: " + name
case User(name, age) if age >= 13 =>
  "Teen: " + name
case User(name, _) =>
  "Child: " + name
end
```

### Guards with Nested Patterns

```jo
match list
case [x, ..rest] if x > 0 && rest.length > 0 =>
  process(x, rest)
case _ =>
  handleEmpty()
end
```

## Assignment Patterns

**Syntax:** `then x = expr, y = expr2, ...`

Assignment patterns allow binding computed values to pattern parameters. They are primarily used in pattern definitions to extract and compute values from the matched data structure. They naturally follow guard patterns when both are present.

**Semantics:**

- The assignments are executed in sequence
- Each assignment evaluates the expression and binds the result to the corresponding pattern parameter
- The pattern parameter identifiers must already be defined in the pattern definition parameters

**Example:**

```jo
// Pattern definition with computed size parameter
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Append(prefix, _) then n = prefix.size + 1
  case Concat(lhs, rhs) then n = lhs.size + rhs.size
end

// Use the pattern
match myList
case Size(n) if n > 10 => "large list"
case Size(n) => "small list"
end
```

### Multiple Assignments

```jo
pattern Stats[T](count: Int, sum: Int): List[Int] =
  case Nil then count = 0, sum = 0
  case Cons(x, tail) then
    count = 1 + tail.count,
    sum = x + tail.sum
end

match numbers
case Stats(count, sum) =>
  println("Count: " + count)
  println("Sum: " + sum)
  println("Average: " + (sum / count))
end
```

### Combining with Guards

```jo
pattern LargeList[T](n: Int): List[T] =
  case Cons(_, tail) if tail.size > 99 then n = 1 + tail.size
end

match list
case LargeList(n) => "Very large: " + n + " elements"
case _ => "Normal size"
end
```

## Summary

| Pattern Form | Syntax | Purpose |
|-------------|--------|---------|
| Literal | `42`, `"hello"` | Match exact values |
| Variable | `x`, `_` | Bind or ignore |
| Type | `x: Type` | Type test and bind |
| Bind | `x @ pattern` | Bind whole and parts |
| Apply | `Some(x)` | Destructure ADTs |
| Sequence | `[x, y, z]` | Match lists |
| Guard | `pattern if cond` | Add conditions |
| Assignment | `then x = expr` | Compute values |

## See Also

- [Pattern Composition](pattern-composition.md) - Combining patterns
- [Semantics](semantics.md) - Pattern matching rules
- [Pattern Definitions](pattern-definitions.md) - Reusable patterns
