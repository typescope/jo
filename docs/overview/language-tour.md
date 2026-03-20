# Language Tour

Welcome to Jo! This tour introduces you to Jo's key language features through examples.

## Hello World

```jo
def main = println "Hello world!"
// Output: Hello world!
```

Every Jo program starts simple. The `def` keyword defines functions, and `println` outputs text.

## Data Types & Pattern Matching

Jo excels at working with structured data through algebraic data types:

```jo
union Expr =
    Abs(x: String, body: Expr)
  | Var(x: String)
  | App(lhs: Expr, arg: Expr)

def show(expr: Expr): String =
  match expr
    case Var x => x
    case Abs x t => "(\\" + x + "." + (show t) + ")"
    case App abs arg => (show abs) + " " + (show arg)
```

Pattern matching provides exhaustive case analysis with compile-time completeness checking.

## Pattern-Oriented Programming

Jo supports custom pattern definitions and pattern composition:

```jo
// Define reusable pattern predicates
pattern Positive: Partial[Int] = case x if x > 0 // (1)!
pattern Even: Partial[Int] = case x if x % 2 == 0

def classify(n: Int): String =
  match n
    case Positive & Even => "positive even" // (2)!
    case Positive => "positive odd"
    case _ => "non-positive"

classify(4)   // => "positive even"
classify(3)   // => "positive odd"
classify(-2)  // => "non-positive"
```

1. `Partial[Int]` means a pattern that may not match all `Int` values
2. Patterns compose with `&` (and), `|` (or), `!` (not)

See [Pattern-Oriented Programming](patterns.md) for advanced features like guarded repeats and the `is` expression.

## Context Parameters

Jo's context parameters provide elegant dependency injection without global variables:

```jo
param env: Env = Empty // (1)!

def find(x: String): Option[Value] =
  match env // (2)!
    case Empty => None
    case Cons k v outer =>
      if x == k then Some(v)
      else find x with env = outer // (3)!
```

1. `param` declares a context parameter with a default value
2. Context parameters are accessed directly by name
3. `with` provides a new value for the context parameter in the call

## Static Capability Control

Jo tracks the usage of capabilities in the type system as context parameters:

```jo
def readFile(path: String): String receives IO.open = // (1)!
  val file = IO.open(path) // (2)!
  val content = file.readLine
  file.close
  content

def processData(text: String): Result receives none = // (3)!
  parseAndValidate(text)
```

1. `receives` declares required capabilities; can be inferred when omitted
2. `IO.open` is the capability for opening files
3. `receives none` proves this function is pure - no side effects allowed

## Natural Syntax

Jo supports flexible call syntax and operators:

```jo
// Multiple call styles
gcd(10, 15)
gcd 10 15
gcd
  10 + x
  15 * y

// Custom operators
def (a: Int) ** (b: Int): Int =
  if b == 0 then 1 else a * (a ** (b - 1))

2 ** 3   // => 8
3 ** 2   // => 9
```

## What's Next?

- [Capability-Oriented Programming](capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](patterns.md) - Advanced pattern features
- [Get Started](../usage/getting-started.md) - Install Jo and run your first program
