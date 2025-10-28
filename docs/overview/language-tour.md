# Language Tour

Welcome to Jo! This tour introduces you to Jo's key language features through practical examples.

## Hello World

```jo
def main = println "Hello world!"
```

Every Jo program starts simple. The `def` keyword defines functions, and `println` outputs text.

## Data Types & Pattern Matching

Jo excels at working with structured data through algebraic data types:

```jo
data Expr =
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

## Effect System

Jo tracks computational effects in the type system:

```jo
def readFile(path: String): String receives IO =
  // Function requires IO capability
  nativeReadFile(path)

def processData(data: String): Result =
  // Pure function - no effects
  parseAndValidate(data)
```

The `receives` clause declares required capabilities, enabling fine-grained security control. The `receives` clause can be inferred when not explicitly specified.

## Natural Syntax

Jo supports flexible call syntax and operators:

```jo
// Multiple call styles
numbers.map(x => x * 2)
numbers map (x => x * 2)

// Custom operators
def (||)(a: Bool, b: Bool): Bool = if a then true else b

// Infix, prefix, postfix all supported
val result = true || false
```