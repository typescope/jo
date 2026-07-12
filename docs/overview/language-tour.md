# Language Tour

A quick tour of Jo's syntax and key features. Each section is self-contained — skip ahead or follow along in order.


## Hello World

Every Jo program starts with `main`. The `def` keyword defines a function; `println` writes a line to stdout.

```jo
def main = println "Hello, Jo!"
```

Output:

```
Hello, Jo!
```

## Variable

`val` declares an immutable binding. `var` declares a mutable one. Types are inferred when not annotated.

```jo
val x = 42              // Int, inferred
val name: String = "Jo" // explicit annotation
var count = 0           // mutable
count = count + 1
println count           // 1
```

See [Value Definitions](../language/definitions/value-definitions.md).

## Function

Function is defined with `def`. Return types are inferred when omitted. Generic type variant functions use `[T]` type annotation.

```jo
def add(x: Int, y: Int): Int = x + y

def greet(name: String) = "Hello, " + name  // return type inferred

def identity[T](x: T): T = x               // generic
```

Jo supports several call styles for the same function:

```jo
def connect(host: String, port: Int = 8080, secure: Bool = false) = ...

connect("localhost", 443, secure = true)           // positional
connect("localhost", port = 443, secure = true)    // named arguments

connect "localhost" 443 true                       // expression call

connect: "localhost", 443, secure = true           // colon call, inlined
connect:                                           // colon call, indented
  "localhost"
  port = 443
  secure = true
```

See [Function Definitions](../language/definitions/function-definitions.md) and [Applications](../language/expressions/applications.md).

## Lambda

Lambdas are anonymous functions that can be assigned to values or passed as arguments.

```jo
val double: Int => Int = x => x * 2
val add = (a: Int, b: Int) => a + b
val greet = () => "Hello!"

[1, 2, 3].map(x => x * 2)          // [2, 4, 6]
```

Lambdas automatically adapt to single-method interfaces (SAM):

```jo
interface Predicate[T]
  def test(x: T): Bool
end

val isEven: Predicate[Int] = x => x % 2 == 0
isEven.test(4)   // true
```

See [Lambdas](../language/expressions/lambdas.md).

## Regular Expression

Regex literals use backtick syntax.

```jo
"abc123".exists(`\d+`)                         // true
"a  b   c".splitBy(`\s+`)                      // ["a", "b", "c"]
"a1b22".replaceAll(`\d+`, _ => "N")            // "aNbN"

if "abc-42" is `(?<w>\w+)-(?<n>\d+)` then
  println w       // abc
  println n       // 42
```

Regex literals can also be used directly in `match` and `is` patterns:

```jo
match input
  case `^\d+$` => "all digits"
  case `^\w+$` => "word"
  case _       => "other"
```

See [Regular Expressions](../language/expressions/regular-expressions.md) and [Regex Patterns](../language/patterns/regex-patterns.md).

## Control Flow

```jo
// if else as expression
val label = if score >= 60 then "pass" else "fail"

// while loop
var i = 0
while i < 10 do
  i = i + 1
  if i % 2 == 0 then continue   // skip even numbers
  if i > 7 then break            // stop early
  println i                      // prints 1, 3, 5, 7

// for loop over a list
for x in [1, 2, 3, 4, 5] do
  if x == 2 then continue        // skip 2
  if x == 5 then break           // stop before 5
  println x                      // prints 1, 3, 4

// rescue handles the error branch of Option or Result inline
val port: Option[Int] = None
val p = port rescue None => 8080           // fallback value

def parse(s: String): Result[Int, String] =
  if s == "" then Err("empty") else Ok(42)

def doubled(s: String): Result[Int, String] =
  val n = parse(s) rescue e @ Err(_) => return e  // propagate on error
  Ok(n * 2)
```

See [Control Flow](../language/expressions/control-flow.md) and [Rescue Expression](../language/expressions/rescue-expression.md).

## Collection

Jo has built-in immutable list, map, and set. Mutable ones are grouped under `mutable` namespace. Lists support splicing with `..`.

```jo
val nums = [1, 2, 3]
val more = [0, ..nums, 4]               // [0, 1, 2, 3, 4]

val scores = Map("alice" ~ 95, "bob" ~ 87)
val tags   = Set("fast", "safe")

val m: mutable.Map[String, Int] = mutable.Map() // Empty mutable map
m["x"] = 10
```

## Union Type

`union` defines a type with multiple variants. Each variant can carry data.

```jo
union Direction = North | South | East | West

union Shape =
    Circle(radius: Float)
  | Rectangle(w: Float, h: Float)
  | Dot
```

The standard `Option` type is defined the same way:

```jo
union Option[T] = Some(value: T) | None
```

See [Union Definitions](../language/definitions/union-definition.md).

## Pattern Matching

`match` dispatches on a union type. The compiler rejects non-exhaustive matches — every variant must be handled.

```jo
def area(shape: Shape): Float =
  match shape
    case Circle r      => 3.14 * r * r
    case Rectangle w h => w * h
    case Dot           => 0.0
```

The `is` expression tests a pattern inline and binds variables into scope:

```jo
val x: Option[Int] = Some(42)

if x is Some(n) then
  println n              // n is bound here

while list is [head, ..tail] do
  println head
  list = tail
```

See [Pattern Forms](../language/patterns/pattern-forms.md).

## Class

Classes can have class parameters or an explicit constructor method. For a class with class parameters, Jo automatically generates a factory function and a pattern from those parameters.

```jo
class Point(x: Int, y: Int)             // class parameters

class Counter
  var count: Int = 0

  def Counter(v: Int): Counter =        // explicit constructor
    this.count = v

  def increment(): Unit = count = count + 1
  def value: Int = count
end

val p = Point(3, 4)
val c = new Counter
c.increment()
println c.value                          // 1
```

See [Class Definitions](../language/definitions/class-definitions.md).

## Interface and View

`interface` defines a contract. A class conforms to it by declaring `view`. The compiler checks conformance at compile time.

```jo
interface Describable
  def describe(): String
end

class Point(x: Int, y: Int)
  def describe(): String = "(\{x}, \{y})"
  view Describable
end

def print(d: Describable): Unit = println d.describe()

print Point(3, 4)                  // (3, 4)
```

A `view` can also delegate to a field — all interface methods are forwarded automatically:

```jo
class Service(logger: Logger)
  view Logger = logger    // Logger methods forwarded to the logger field
end
```

See [Interface Definitions](../language/definitions/interface-definitions.md).

## Context Parameter

`param` declares a context parameter — a value threaded implicitly through the call stack without explicit argument passing. `with ... in` overrides it for a scoped block.

```jo
param indent: Int = 0

def line(text: String): Unit =
  println: " " * indent + text

def list(title: String, items: List[String]): Unit =
  line title
  with indent = indent + 2 in
    for item in items do line item

def main =
  list("Colors", ["red", "green", "blue"])
```

Output:

```
Colors
  red
  green
  blue
```

See [Context Parameters](../language/definitions/context-parameters.md).

## Capability

`receives` declares what capabilities a function requires. `receives none` means the function is provably pure — the compiler verifies the entire call chain. `allow` enforces the boundary at a specific call site.

```jo
// Pure: the compiler rejects any IO or side effect inside
def double(x: Int): Int receives none = x * 2

// Needs stdout — nothing else
def greet(name: String): Unit receives IO.stdout =
  println "Hello, \{name}!"

def main receives IO.stdout =
  greet("world")

  // allow none verifies at the call site that double uses no capabilities
  val result = allow none in double(21)
  println result                         // 42
```

## What's Next?

- [Capability-Based Programming](capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](../guides/patterns.md) - Advanced pattern features
- [Cheat Sheet](cheat-sheet.md) - Quick reference for Jo syntax
- [Get Started](../usage/getting-started.md) - Install Jo and run your first program
