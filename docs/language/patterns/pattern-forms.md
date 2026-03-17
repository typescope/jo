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

Matches sequences (lists, arrays) using atom patterns and repeat patterns.

```jo
match list
case [] => "empty"
case [x] => "singleton: " + x
case [x, y] => "pair: " + x + ", " + y
end
```

### Repeat Patterns

Match variable-length subsequences:

```jo
match list
case [head, ..tail] =>
  // head is the first element
  // tail is the rest of the list
  head + sum(tail)
end
```

### Guarded Repeat Patterns

Match elements while a condition holds:

```jo
match numbers
case [..positives while Positive, ..rest] =>
  // positives: all leading positive numbers
  // rest: remaining numbers
  "Found " + positives.length + " positive numbers"
end
```

For detailed specification of sequence patterns, see [Sequence Patterns](sequence-patterns.md).

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

## Assignment Patterns

**Syntax:** `then x = expr, y = expr2, ...`

Assignment patterns allow binding computed values to pattern parameters. They are primarily used in pattern definitions to extract and compute values from the matched data structure. They naturally follow guard patterns when both are present.

**Semantics:**

- The assignments are executed in sequence
- Each assignment evaluates the expression and binds the result to the corresponding pattern parameter
- The pattern parameter identifiers must already be defined in the flow scope

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

### Default Values in Or-Patterns

Assignment patterns enable uniform binding in or-patterns by providing default values:

```jo
// Provide a default value for None case
match maybeCount
case Some(x) | (None then x = 0) =>
  println("Count: " + x)
end

// Multiple branches with defaults
match result
case Ok(value) | (Err(_) then value = -1) =>
  processValue(value)
end

// Complex default computation
match config
case Full(host, port) | (Partial(host) then port = 8080) =>
  connect(host, port)
end
```

This pattern is particularly useful when you want to handle multiple cases uniformly but need to supply default values for branches that don't naturally bind certain variables.

## Expression Patterns

**Syntax:** `simple_pattern {simple_pattern}`

A sequence of simple patterns juxtaposed without operators. The interpretation depends on the pattern context—typically used for applying infix pattern operators.

::: info Pattern expression syntax

Pattern expressions use the same rules as term expressions and type expressions: only operator expressions and shape expressions are supported, only terms support precedence expressions.

```jo
// parsed as: ((!(Some(x))) & Positive) | (!Even)
case !Some(x) & Positive | !Even => ...
```

See [Expression syntax](../concepts/expression-syntax.md) for more detais.
:::
## Or Patterns

**Syntax:** `pattern₁ | pattern₂`

Matches if either `pattern₁` or `pattern₂` matches. Both patterns must bind the same set of variables with compatible types.

Defined in `Predef.jo` using the infix pattern operator `|[T]`.

```jo
match x
case 0 | 1 | 2 => "small"
case 3 | 4 | 5 => "medium"
case _ => "large"
end

match token
case Ident(name) | Keyword(name) | Operator(name) =>
  // name is bound in all branches
  println(name)
end
```

### Uniform Binding Requirement

All branches must bind exactly the same set of variables:

```jo
// ✓ OK - both bind x
match either
case Left(x) | Right(x) => x
end

// ❌ Error - Left binds x, Right binds y
match result
case Left(x) | Right(y) => ...
end

// ❌ Error - first binds x and y, second binds only x
match result
case Pair(x, y) | Single(x) => ...
end
```

::: info Design Rationale: Uniform Binding Requirement
OR-patterns require all branches to bind the same variables (not just a common subset) to prevent accidental errors. If we allowed different sets, forgetting to bind a variable in one branch would go unnoticed—the variable would simply be excluded from the common set. By requiring uniform bindings, the compiler catches these mistakes immediately.
:::
## And Patterns

**Syntax:** `pattern₁ & pattern₂`

Matches if both `pattern₁` and `pattern₂` match the scrutinee. Both patterns match against the same value.

Defined in `Predef.jo` using the infix pattern operator `&[T]`.

```jo
match n
case Positive & Even => "positive even number"
case Positive => "positive odd number"
case _ => "not positive"
end
```

### Variable Binding in And-Patterns

A variable is definitely bound if it is definitely bound in either `pattern₁` or `pattern₂`. It is an error if a variable is definitely bound in both patterns.

```jo
// ✓ OK - x bound in first pattern, y in second
case (x, _) & (_, y) => ...

// ❌ Error - x bound in both patterns
case Some(x) & Just(x) => ...
```

## Not Patterns

**Syntax:** `!pattern`

Matches if the nested `pattern` does **not** match the scrutinee. This is the logical negation of a pattern.

Defined in `Predef.jo` using the prefix pattern operator `![T]`.

```jo
match n
case !Positive => "not positive (zero or negative)"
case _ => "positive"
end

match value
case !None => "has a value"
case None => "no value"
end
```

### Variable Binding in Not-Patterns

Variables bound within a not-pattern are **not available** in the match branch, since the pattern succeeds only when the nested pattern fails to match.

```jo
// ✓ OK - no variables bound
case !Positive => "not positive"

// ❌ Error - x would be bound when pattern fails
case !(Some(x)) => x  // x is not bound here
```

### Combining Not-Patterns

Not-patterns can be combined with or-patterns and and-patterns:

```jo
// Match values that are neither positive nor even
match n
case !Positive & !Even => "not positive and not even"
case _ => "positive or even (or both)"
end

// Match values that are not (positive and even)
match n
case !(Positive & Even) => "not a positive even number"
case _ => "positive even number"
end

// Match values that don't satisfy either condition
match n
case !Positive | !Even => "not positive or not even"
case _ => "positive and even"
end
```

## Parenthesized Patterns

**Syntax:** `(pattern)`

Groups a pattern. Useful for:
- Controlling precedence
- Adding guards to nested patterns

```jo
// Control precedence
match x
case (0 | 1) & Positive => ...  // (0 | 1) grouped
end

// Guard on nested pattern
match x
case Some((y if y > 0)) => "some positive"
case Some(_) => "some non-positive"
case None => "none"
end

// Complex composition
match result
case (Ok(x) | Err(x)) & (y if y.isValid) =>
  process(y)
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
| Or | `p₁ | p₂` | Match either pattern |
| And | `p₁ & p₂` | Match both patterns |
| Not | `!p` | Match negation of pattern |
| Parenthesized | `(pattern)` | Group patterns |

## See Also

- [Semantics](semantics.md) - Pattern matching rules
- [Pattern Definitions](pattern-definitions.md) - Reusable patterns
