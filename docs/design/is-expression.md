# Is Expression

## Overview

The `is` expression provides a boolean test for pattern matching. It evaluates to `true` if a value matches a pattern, `false` otherwise. When used as the condition of `if` or `while`, variables bound by the pattern become available in the consequent branch or loop body through flow typing.

## Motivation

Pattern matching is powerful but requires explicit branching with `match`. For simple boolean tests, this can be verbose:

```jo
// Without is expression - verbose
val hasValue = x match
  case Some(_) => true
  case None => false
end

// With is expression - concise
val hasValue = x is Some(_)
```

When combined with flow typing in `if` and `while`, `is` expressions enable concise conditional logic with pattern matching:

```jo
// Check and extract in one step
if x is Some(value) then
  println(value)  // value is available here
end

// Process list elements
while queue is Cons(head, tail) do
  process(head)
  queue = tail
end
```

## Syntax

```
is_expression = word "is" simple_pattern
```

The `is` expression consists of:
- A word (scrutinee expression)
- The keyword `is`
- A `simple_pattern` (as defined in the pattern language)

Since `(pattern)` is a `simple_pattern`, guards and assignments can be used via parentheses:

```jo
// Basic pattern
x is Some(y)

// With guard (using parentheses)
x is (Some(y) if y > 0)

// With assignment (using parentheses)
list is (Size(n) if n > 10)

// Multiple bindings
pair is (x, y)
```

## Semantics

The `is` expression evaluates as follows:

1. Evaluate the word (scrutinee) to a value `v`
2. Attempt to match `v` against `simple_pattern`
3. Return `true` if the pattern matches, `false` otherwise
4. Pattern variables are **not** bound in the surrounding scope (unlike in `match` expressions)

```jo
val result = x is Some(y)
// result is true if x matches Some(_), false otherwise
// y is NOT available here
```

## Flow Typing Integration

When an `is` expression appears as the direct condition of an `if` expression or `while` loop, variables bound by the pattern become available in the appropriate scope through flow typing.

### If Expressions

```jo
if word is simple_pattern then
  // Variables bound by simple_pattern are available here
  consequent
else
  // Bindings NOT available here
  alternative
end
```

**Example:**

```jo
if x is Some(value) then
  println(value)  // value is available and has appropriate type
else
  println("None")
end
```

### While Loops

```jo
while word is simple_pattern do
  // Variables bound by simple_pattern are available here
  body
end
```

**Example:**

```jo
while queue is Cons(head, tail) do
  process(head)  // head and tail are available
  queue = tail   // Update for next iteration
end
```

### Limitation: No Propagation Through Boolean Operators

Flow typing does **not** propagate through boolean operators (`&&`, `||`, `!`):

```jo
// This does NOT work
if x is Some(y) && y > 0 then
  println(y)  // ERROR: y not available here
end

// Use parentheses with guard instead
if x is (Some(y) if y > 0) then
  println(y)  // OK: y is available
end
```

**Rationale:** Propagating flow typing through boolean operators adds significant complexity:

- `&&`: Would need to track bindings from left operand into right operand
- `||`: Would need to merge bindings from both branches (challenging when branches bind different variables)
- `!`: Negation makes binding semantics unclear

The parenthesized guard pattern `(pattern if condition)` provides equivalent expressiveness without this complexity.

## Examples

### Basic Pattern Testing

```jo
// Test if option has a value
if opt is Some(_) then
  println("Has value")
end

// Test list structure
val isEmpty = list is []
val isSingleton = list is [_]
```

### Extracting Values

```jo
// Extract and use in one step
if user is ValidUser(name, age) then
  println("Welcome, " + name + " (age: " + age + ")")
end

// Extract nested structure
if response is Success(data) then
  process(data)
end
```

### While Loop Processing

```jo
// Process list elements
while list is Cons(head, tail) do
  println(head)
  list = tail
end

// Drain a queue
while queue is NonEmpty(item, rest) do
  handleItem(item)
  queue = rest
end
```

### Using Assignment Patterns

```jo
// Pattern with computed values
if tree is (Height(h) if h > 10) then
  println("Tall tree: " + h)
end

// Check list size
if list is (Size(n) if n > 100) then
  println("Large list")
end
```

### Combining with Other Expressions

```jo
// In boolean expressions
val isValid = input is ValidFormat(_) && checksum(input)

// As function argument (no flow typing)
processIf(x is Some(_), "has value", "is none")

// In variable initialization
val hasError = result is Error(_)
```

### Type Refinement

```jo
// Type testing
if value is (x: Int) then
  println("Integer: " + x)
else if value is (s: String) then
  println("String: " + s)
end

// With type and structure
if shape is (c: Circle) then
  println("Circle radius: " + c.radius)
end
```

## Design Decisions

### Why `simple_pattern` Instead of Full `pattern`?

The syntax uses `simple_pattern` rather than full `pattern` to avoid ambiguity. However, since `(pattern)` is a `simple_pattern`, guards and assignments are technically available via parentheses:

```jo
// Technically allowed but BAD STYLE
if x is (Some(y) if y > 0 then z = y * 2) then
  println(z)
end
```

**This is considered bad style.** For complex patterns with guards or assignments, define a named pattern predicate instead (see "Named Pattern Predicates for Complex Patterns" below).

### Why No Flow Typing Through Boolean Operators?

Boolean operators (`&&`, `||`, `!`) do not propagate flow typing bindings. Users must use guards within patterns instead:

```jo
// Instead of: if x is Some(y) && y > 0 then ...
// Write: if x is (Some(y) if y > 0) then ...
```

For better readability with complex conditions, define a named pattern predicate:

```jo
// Best: named pattern predicate
pattern PositiveValue(y: Int): Partial[Option[Int]] =
  case Some(y) if y > 0

if x is PositiveValue(y) then ...
```

**Rationale:**

1. **Complexity:** Tracking bindings through boolean operators is complex:

     - `&&` requires flowing bindings from left to right
     - `||` requires merging bindings from both branches
     - `!` has unclear binding semantics

2. **Alternative exists:** Guard patterns provide equivalent expressiveness

3. **Simplicity:** Keeps flow typing rules local and predictable

4. **Better style:** Named pattern predicates make complex conditions more readable and reusable

### Why Pattern Variables Not Available Outside Flow Typing?

```jo
val matched = x is Some(y)
// y is NOT available here
```

Pattern variables are only available when `is` appears as the direct condition of `if`/`while`. In other contexts (boolean expressions, function arguments, etc.), the pattern matching succeeds or fails, but bindings are not propagated.

**Rationale:**

1. **Scope clarity:** Bindings only exist where they're useful (in conditionally executed code)
2. **No implicit state:** Avoids "spooky action at a distance" where evaluating an expression has binding side effects
3. **Consistent with match:** In `match` expressions, bindings are local to case branches

### Named Pattern Predicates for Complex Patterns

For complex patterns used repeatedly, define a named pattern predicate instead of inline patterns with guards:

```jo
// Define a reusable pattern predicate
pattern Positive(x: Int): Partial[Option[Int]] =
  case Some(x) if x > 0

// Use the named pattern - clearer intent
if res is Positive(x) then
  println("Positive value: " + x)
end
```

This is more readable than the inline version:

```jo
// Inline pattern with guard - harder to read
if res is (Some(x) if x > 0) then
  println("Positive value: " + x)
end
```

**Benefits of named pattern predicates:**

- **Readability:** Intent is clear from the pattern name
- **Reusability:** Pattern can be used in multiple places
- **Maintainability:** Change the pattern logic in one place
- **Documentation:** Pattern name documents the meaning

**Examples:**

```jo
// Validation patterns
pattern ValidEmail: Partial[String] =
  case addr if addr.contains("@") if addr.length > 3

pattern NonEmptyList[T](head: T, tail: List[T]): Partial[List[T]] =
  case Cons(head, tail)

pattern LargeTree[T](size: Int): Partial[Tree[T]] =
  case tree if tree.size > 100 then size = tree.size

// Usage
if input is ValidEmail then
  sendTo(input)
end

if list is NonEmptyList(first, rest) then
  process(first)
end

if data is LargeTree(n) then
  println("Tree has " + n + " nodes")
end
```

## Comparison with Other Languages

### Kotlin

Kotlin has `is` for type testing with smart casts:

```kotlin
if (x is String) {
    println(x.length)  // x is smart-cast to String
}
```

Jo's `is` expression is more general, supporting full pattern matching, not just type tests.

### Swift

Swift has pattern matching in `if` and `while`:

```swift
if case .some(let value) = optionalValue {
    print(value)
}
```

Jo's syntax is more concise with `is` instead of `case ... =`.

### Rust

Rust has `if let` and `while let`:

```rust
if let Some(value) = x {
    println!("{}", value);
}
```

Jo's `is` is more general - it's an expression that returns `Bool`, not a special statement form.

### F\#

F# uses pattern matching guards:

```fsharp
match x with
| Some(v) when v > 0 -> printfn "%d" v
| _ -> ()
```

Jo's `is` expression provides a lightweight alternative for simple cases without full `match`.
