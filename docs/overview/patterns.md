# Pattern-Oriented Programming

Jo elevates pattern matching from a simple control structure to a core programming paradigm. Beyond destructuring data, patterns become reusable abstractions that can be defined, composed, and passed around.

## Why Patterns Matter

Traditional pattern matching in functional languages is powerful but limited: patterns are syntactic constructs tied to specific `match` expressions. Jo breaks this limitation by making patterns first-class citizens:

- **Reusable** - Define patterns once, use them everywhere
- **Composable** - Combine patterns with `&` (and), `|` (or), and `!` (not)
- **Expressive** - Sequence patterns match variable-length data with guards

This enables a declarative style where complex conditions become clear, readable patterns.

## Pattern Definitions

Define reusable pattern predicates that encapsulate matching logic:

```jo
// Simple predicates
pattern Positive: Partial[Int] = case x if x > 0
pattern Even: Partial[Int] = case x if x % 2 == 0

// Use in match expressions
match n
  case Positive => "positive"
  case Even => "even"
  case _ => "other"

// Use in boolean tests
if n is Positive then println "n is positive"
```

Pattern predicates can also extract values through parameters:

```jo
pattern Name(name: String): Student =
  case s then name = s.name

pattern Len(n: Int): List[Int] =
  case [] then n = 0
  case [_, ..xs] if xs is Len(acc) then n = 1 + acc

// Usage
match student case Name(n) => println "Name: \{n}"
match myList case Len(size) => println "Size: \{size}"
```

The `Partial[T]` type indicates that a pattern may not match all values of type `T`.

## Pattern Composition

Patterns compose naturally with logical operators, enabling expressive conditions:

```jo
pattern Positive: Partial[Int] = case x if x > 0
pattern Even: Partial[Int] = case x if x % 2 == 0

def classify(n: Int): String =
  match n
    case Positive & Even => "positive even"
    case Positive => "positive odd"
    case !Positive & Even => "non-positive even"
    case _ => "non-positive odd"

// Boolean expressions with patterns
val isPositiveEven = n is (Positive & Even)
val isNotPositive = n is (!Positive)
```

Pattern operators follow intuitive semantics:

- `p1 & p2` - Matches when both patterns match
- `p1 | p2` - Matches when either pattern matches
- `!p` - Matches when the pattern does not match

## Sequence Patterns

Sequence patterns match lists and arrays with powerful features for handling variable-length data:

```jo
match list
  case [] => "empty"
  case [x] => "singleton"
  case [x, y] => "pair"
  case [first, ..middle, last] => "at least two elements"
```

### Guarded Repeats

Match elements while a condition holds:

```jo
pattern Positive: Partial[Int] = case x if x > 0

match numbers
  case [..positives while Positive, ..rest] =>
    println "positives = \{positives}, rest = \{rest}"
```

For `[1, 2, 3, -1, 4]`, this binds `positives = [1, 2, 3]` and `rest = [-1, 4]`.

### Real-World Example: Email Validation

Sequence patterns excel at text parsing:

```jo
def checkEmail(email: String): Unit =
  pattern ValidChar: Partial[Char] = case !'@' & !' '

  if email is [..lhs while ValidChar, '@', ..rhs while ValidChar] then
    println "valid email: lhs = \{lhs}, rhs = \{rhs}"
  else
    println "invalid email"
```

This pattern reads naturally: match characters that are not `@` or space, then `@`, then more valid characters.

## The `is` Expression

The `is` expression brings pattern matching into boolean contexts with flow typing - variables bound by the pattern become available in subsequent code:

```jo
// Extract and validate in one expression
val isPositive = x is Some(value) && value > 0

// Use in if conditions
if user is ValidUser(name, age) && age >= 18 then
  println "Welcome, \{name}!"

// Process lists with while loops
var list = [1, 2, 3, 4, 5]
while list is [head, ..tail] do
  println head
  list = tail
```

This eliminates the need for nested `match` expressions when you just need a boolean test with extraction:

```jo
// Without is - verbose
val isPositive = match x
  case Some(v) => v > 0
  case None => false

// With is - concise and clear
val isPositive = x is Some(v) && v > 0
```

Combined with pattern definitions, `is` expressions become even more powerful:

```jo
pattern Positive(x: Int): Partial[Option[Int]] =
  case Some(x) if x > 0

if result is Positive(value) then
  println "Positive value: \{value}"
```

## Custom Infix Patterns

Define your own pattern syntax using infix operators:

```jo
// Define a cons pattern for lists
pattern (head: T) :: [T](tail: List[T]): Partial[List[T]] =
  case [head, ..tail]

// Use the infix pattern
match list
  case x :: xs => println "head = \{x}, tail = \{xs}"
  case _ => println "empty"
```

## Further Reading

- [Pattern Language Reference](../language/patterns/overview.md) - Complete pattern syntax
- [Pattern Definitions](../language/patterns/pattern-definitions.md) - Defining reusable patterns
- [Sequence Patterns](../language/patterns/sequence-patterns.md) - Matching variable-length data
- [Is Expression](../language/expressions/is-expression.md) - Flow typing with patterns
