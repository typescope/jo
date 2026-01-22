# Language Tour

Welcome to Jo! This tour introduces you to Jo's key language features through examples.

## Hello World

```jo
def main = println "Hello world!"
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

Jo makes it easy to work with patterns with the support for custom pattern
definitions and sequence patterns:

```Scala
def checkEmail(email: String): Unit =
  pattern ValidChar: Partial[Char] = case !'@' & !' '

  if email is [..lhs while ValidChar, '@', ..rhs while ValidChar] then
    println "valid email: lhs = \{lhs}, rhs = \{rhs}"

  else
    println "invalid email"

def testNamedGuarded =
  val list = [-1, -2, 3, 4, 5]
  pattern Pos: Partial[Int] = case x if x > 0
  match list
    case [..small while Pos, ..rest] =>
      println "pos = \{small}, rest = \{rest}"
    case _ =>
```

## Context Parameters

Jo's context parameters provide elegant dependency injection without global variables:

```jo
param env: Env = Empty  // environment for variables

def find(x: String): Option[Value] =
  match env
    case Empty => None
    case Cons k v outer =>
      if x == k then Some(v)
      else find x with env = outer
```

The `param` declares a contextual parameter. Functions can access `env` directly or override it with `with env = newValue`.

## Static Capability Control

Jo tracks the usage of capabilities in the type system as context parameters:

```jo
def readFile(path: String): String receives IO.open =
  // Function requires file opening capability
  val file = open(path)
  val content = file.readLine
  file.close
  content

def processData(text: String): Result receives none =
  // Pure function - no effects
  parseAndValidate(text)
```

The `receives` clause declares required capabilities to produce effects, enabling compile-time security control. The `receives` clause can be inferred when not explicitly specified.

## Natural Syntax

Jo supports flexible call syntax and operators:

```jo
// Multiple call styles
gcd(10, 15)
gcd 10 15
gcd
  10
  15

// Custom operators
def (a: Int) ** (b: Int): Int =
  if b == 0 then 1 else a * (a ** (b - 1))

// Infix and prefix operators
val result = 2 ** 3  // result is 8
```
