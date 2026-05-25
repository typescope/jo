# Language Tour

Welcome to Jo! This tour introduces Jo's key language features through examples.

## Hello World

```jo
def main = println "Hello world!"
// Output: Hello world!
```

Every Jo program starts simple. The `def` keyword defines functions, and `println` outputs text.

## Basic Types

Jo's primitive types are `Int`, `Float`, `Bool`, `Char`, and `String`:

```jo
42          // Int
3.14        // Float
true        // Bool
'a'         // Char
"hello"     // String
```

Variables are declared with `val` (immutable) or `var` (mutable):

```jo
val x = 42              // type inferred
val name: String = "Jo" // with annotation
var count = 0           // mutable
count = count + 1
```

## Function and Lambda

Functions are defined with `def`. Return types are inferred when omitted:

```jo
def greet(name: String): String = "Hello, " + name

def add(x: Int, y: Int) = x + y   // return type inferred

def identity[T](x: T): T = x      // generic function
```

Parameters can have default values and can be passed by name at the call site:

```jo
def connect(host: String, port: Int = 8080, secure: Bool = false): Connection = ...

connect("localhost")                               // use defaults
connect("example.com", port = 443, secure = true) // named arguments
connect("example.com", 443, true)                 // positional
```

A pre-parameter placed before the function name makes it callable as a method:

```jo
def (xs: List[Int]) sum: Int =   // xs is the receiver
  xs.foldLeft(0, (a, b) => a + b)

[1, 2, 3].sum   // => 6
```

Lambdas use `=>` syntax:

```jo
val double: Int => Int = x => x * 2
val multiply = (x: Int, y: Int) => x * y
```

Custom operators are defined like functions and used infix:

```jo
def (a: Int) ** (b: Int): Int =
  if b == 0 then 1 else a * (a ** (b - 1))

2 ** 10   // => 1024
```

## Flexible Call Syntax

Jo supports multiple call styles for the same function:

```jo
connect("example.com", 443, true)                  // parenthesized
connect "example.com" 443 true                     // space-separated
connect: "example.com", port = 443, secure = true  // colon call, inline
connect:                                           // colon call, indented
  "example.com"
  port = 443
  secure = true
```

## Class and Interface

Classes can have constructor parameters or an explicit body:

```jo
class Point(x: Int, y: Int)   // constructor parameters

class Counter                  // explicit body
  var count: Int = 0
  def increment(): Unit = count = count + 1
  def value: Int = count
end
```

Interfaces define a contract; classes implement it via `view`:

```jo
interface Printable
  def print(): Unit
end

class NamedPoint(x: Int, y: Int, name: String)
  def print(): Unit = println "\{name}: (\{x}, \{y})"
  view Printable
end
```

A `view` can also delegate to a field:

```jo
class Service(logger: Logger)
  view Logger = logger   // all Logger methods delegated to field
end
```

## Data Structs

Jo has built-in list, map, and set literals:

```jo
val nums = [1, 2, 3]
val extended = [0, ..nums, 4]           // splicing

val scores = Map("alice" ~ 95, "bob" ~ 87)
val ids = Set(1, 2, 3)
```

Algebraic data types are defined with `union`:

```jo
union Shape =
    Circle(radius: Float)
  | Rectangle(w: Float, h: Float)
  | Dot

def area(shape: Shape): Float =
  match shape
    case Circle r => 3.14 * r * r
    case Rectangle w h => w * h
    case Dot => 0.0
```

## Extension

Extensions add methods to existing types without modifying them:

```jo
extension ListOps[T] for List[T]
  def isEmpty: Bool = xs is []
  def first: T = match xs case [x, .._] => x
end

val nums = [1, 2, 3]
nums.isEmpty   // false
nums.first     // 1
```

Extensions are opt-in — they only apply where imported, keeping the type system predictable.

## Patterns

Jo supports named, reusable pattern predicates that can be composed with logical operators:

```jo
pattern Positive: Partial[Int] = case x if x > 0
pattern Even: Partial[Int] = case x if x % 2 == 0

match n
  case Positive & Even => "positive even"
  case Positive        => "positive odd"
  case _               => "non-positive"
```

The `is` expression tests a pattern inline and binds variables into the surrounding scope:

```jo
if x is Some(value) then println value

while list is [head, ..tail] do
  println head
  list = tail
```

Pattern matching on union types is exhaustive — the compiler rejects non-exhaustive matches:

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

See [Pattern-Oriented Programming](patterns.md) for sequence patterns, guarded repeats, and extracting patterns.

## Context Parameters

Context parameters are implicit values threaded through the call stack without being passed explicitly at every call site:

```jo
param indent: Int = 0 // (1)!

def printLine(text: String): Unit =
  println " " * indent + text // (2)!

def printSection(title: String, items: List[String]): Unit =
  printLine title
  for item in items do
    printLine item with indent = indent + 2 // (3)!
```

1. `param` declares a context parameter with a default value
2. Context parameters are accessed directly by name — no threading required
3. `with` overrides the context parameter for a specific call; inner calls inherit the new value automatically

## Static Capability Control

`receives` declares which capabilities a function requires. The compiler tracks this statically — a function cannot use a capability it hasn't declared:

```jo
def readFile(path: String): String receives os = // (1)!
  val file = os.open(path) // (2)!
  val content = file.readLine
  file.close
  content

def processData(text: String): String receives none = // (3)!
  parseAndValidate(text)
```

1. `receives os` declares that this function requires the `os` capability
2. `os` is the filesystem capability — only functions that declare it can call `os.open`
3. `receives none` proves the function is pure — the compiler verifies no capabilities are used

Capabilities are granted at the call site with `allow`:

```jo
allow os in readFile("config.txt")   // grant the os capability
allow none in processData("hello")   // verify the call is pure
```

## What's Next?

- [Capability-Oriented Programming](capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](patterns.md) - Advanced pattern features
- [Cheat Sheet](cheat-sheet.md) - Quick reference for Jo syntax
- [Get Started](../usage/getting-started.md) - Install Jo and run your first program
