# Pattern-Oriented Programming

Jo elevates pattern matching from a simple control structure to a core programming paradigm.

## Why Patterns Matter

Traditional pattern matching in functional languages is powerful but limited: patterns are syntactic constructs tied to `match` expressions. Jo breaks this limitation by making patterns first-class citizens:

- **Reusable** - Define patterns once, use them everywhere
- **Composable** - Combine patterns with `&` (and), `|` (or), and `!` (not)
- **Expressive** - Sequence patterns match variable-length data with guards

This enables a declarative style where complex conditions become clear, readable patterns.

## Pattern Definitions

Define reusable pattern predicates that encapsulate matching logic:

```jo
pattern Positive: Partial[Int] = case x if x > 0 // (1)!
pattern Even: Partial[Int] = case x if x % 2 == 0

match n
  case Positive => "positive"
  case Even => "even"
  case _ => "other"

if n is Positive then println "n is positive" // (2)!
```

1. `Partial[Int]` - a pattern that may not match all `Int` values (a partial function)
2. `is` tests if a value matches a pattern, with flow typing for bound variables

Pattern predicates can also extract values through parameters:

```jo
pattern Name(name: String): Student = // (1)!
  case s then name = s.name // (2)!

pattern Len(n: Int): List[Int] =
  case [] then n = 0
  case [_, ..xs] if xs is Len(acc) then n = 1 + acc // (3)!

match student case Name(n) => println "Name: \{n}"
match myList case Len(size) => println "Size: \{size}"
```

1. Pattern parameters in parentheses are output bindings, extracted on match
2. `then name = ...` binds the output parameter when the pattern matches
3. Patterns can be recursive and use other patterns via `is`

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

classify(4)   // => "positive even"
classify(3)   // => "positive odd"
classify(-2)  // => "non-positive even"
classify(-1)  // => "non-positive odd"
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
  case [..positives while Positive, ..rest] => // (1)!
    println "positives = \{positives}, rest = \{rest}"
```

1. `..xs while P` matches elements while pattern `P` holds, binding them to `xs`

For `[1, 2, 3, -1, 4]`, this binds `positives = [1, 2, 3]` and `rest = [-1, 4]`.

### Real-World Example: Extract code from LLM response

Regex patterns excel at text parsing:

```jo
// enable option "s" to allow . to match new line
if message is #r[s]"<code>(?<prog>.*)</code>" then
  println prog
```

The named group `prog` in the regex pattern becomes a variable of the type `String`.

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
  println head   // Output: 1, 2, 3, 4, 5 (one per line)
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
