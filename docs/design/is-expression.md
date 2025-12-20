# Is Expression

## Overview

The `is` expression provides a boolean test for pattern matching. It evaluates to `true` if a value matches a pattern, `false` otherwise. Variables bound by the pattern become available through flow typing in control flow constructs (`if`, `while`) and boolean expressions (`&&`, `||`).

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

Flow typing enables variables bound by `is` to be used immediately in boolean expressions and control flow:

```jo
// Extract and validate in one expression
val isPositive = x is Some(value) && value > 0

// Check and extract in if condition
if x is Some(value) then
  println(value)  // value is available here

// Process list elements
while queue is Cons(head, tail) do
  process(head)
  queue = tail
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
p is Point(x, y)
```

## Semantics

The `is` expression evaluates as follows:

1. Evaluate the word (scrutinee) to a value `v`
2. Attempt to match `v` against `simple_pattern`
3. Return `true` if the pattern matches, `false` otherwise

## Flow Typing

Variables bound by `is` expressions become available in subsequent code through
flow typing. This works both in control flow constructs (`if`, `while`) and in
boolean expressions (`&&`, `||`).

### Conditional Expression

Similar to pattern-level flow typing, term-level flow typing is a flat scope
that works on a _conditional expression_.  A conditional expression is
recursively defined as follows:

- An is-expression is a conditional expression
- Two words joined by `||` or `&&` is a conditional expression
- A word negated by `!` is a conditional expression

Generally, the names bound in a conditional expression will not be available
outside the conditional expression.

However, if the condition of `if/else` or `while` is a conditional expression,
the bound names in which will be available in typing the body of `if/then` and
`while`.

### Boolean Operators

**For `&&` (conjunction):**

- Variables bound in the left operand are available in the right operand
- Variables bound in either operand are available after the entire expression

```jo
if x is Some(y) && y > 0 then
  println(y)  // y is available
```

**For `||` (disjunction):**

- Variables bound in the left operand are NOT available in the right operand
- Only variables bound in BOTH branches are available after the expression
- If branches bind different variables, it's a type error

```jo
// Both branches bind 'value'
if x is Some(value) || default is Some(value) then
  println(value)  // OK: value bound in both branches
```

**For negation (`!`):**

- Variables bound within the negated expression are NOT available after the negation

These rules mirror how patterns work with `&` and `|` operators, providing consistent semantics across patterns and boolean expressions.

### Control Flow

**For `if` expressions:**

- Variables bound by the conditional expression are available in the then-branch
- Bindings are NOT available in the else-branch

```jo
if x is Some(value) then
  println(value)  // value is available
else
  println("None")  // value is NOT available
```

**For `while` loops:**

- Variables bound by the conditional expression are available in the loop body

```jo
while queue is Cons(head, tail) do
  process(head)  // head and tail are available
  queue = tail
```

### When Variables Are Not Available

Variables bound by `is` are NOT available when the expression is used in isolation (outside boolean or control flow contexts):

```jo
// No flow typing - standalone expression
val result = x is Some(y)
// result is true if x matches Some(_), false otherwise
// y is NOT available here
```

## Examples

### Basic Pattern Testing

```jo
// Test if option has a value
if opt is Some(_) then
  println("Has value")

// Test list structure
val isEmpty = list is []
val isSingleton = list is [_]
```

### Extracting Values

```jo
// Extract and use in one step
if user is ValidUser(name, age) then
  println("Welcome, " + name + " (age: " + age + ")")

// Extract nested structure
if response is Success(data) then
  process(data)
```

### While Loop Processing

```jo
// Process list elements
while list is Cons(head, tail) do
  println(head)
  list = tail

// Drain a queue
while queue is NonEmpty(item, rest) do
  handleItem(item)
  queue = rest
```

### Combining with Other Expressions

```jo
// In boolean expressions with flow typing
val isValid = input is ValidFormat(data) && checksum(data)

// Extract and validate
val isPositive = x is Some(value) && value > 0

// As function argument
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

// With type and structure
if shape is (c: Circle) then
  println("Circle radius: " + c.radius)
```

## Comparison with Other Languages

### Kotlin

Kotlin has `is` for type testing with smart casts:

```kotlin
if (x is String) {
    println(x.length)  // x is smart-cast to String
}
```

### Swift

Swift has pattern matching in `if` and `while`:

```swift
if case .some(let value) = optionalValue {
    print(value)
}
```

### Rust

Rust has `if let` and `while let`:

```rust
if let Some(value) = x {
    println!("{}", value);
}
```

### F\#

F# uses pattern matching guards:

```fsharp
match x with
| Some(v) when v > 0 -> printfn "%d" v
| _ -> ()
```
