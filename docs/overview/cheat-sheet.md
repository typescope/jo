# Cheat Sheet

A quick reference for Jo syntax. See the [Language Tour](language-tour.md) for a narrative introduction.

## Literals

```jo
42                          // Int
3.14                        // Float
1_000_000                   // underscores for readability
0xFF_FF                     // hexadecimal
6.022e23                    // scientific notation
true  false                 // Bool
'a'  '\n'  '\u{1F600}'     // Char
```

## Strings

```jo
"hello \{name}"                   // interpolation with \{...}
"line1\nline2"                    // escape sequences

"""                               // multi-line, vertical trim by ending """
  SELECT *
  FROM users
  WHERE id = \{id}
  """
```

## Variables

```jo
val x = 42                        // immutable
val y: String = "typed"           // with type annotation
var counter = 0                   // mutable
counter = counter + 1             // assignment
```

## Functions

```jo
def greet(name: String): String = "Hello, " + name

def add(x: Int, y: Int) = x + y  // return type inferred

def (a: Int) ** (b: Int): Int =   // operator definition
  if b == 0 then 1 else a * (a ** (b - 1))

def (xs: List[Int]) sum: Int =    // pre-param (method-style)
  xs.foldLeft(0, (a, b) => a + b)

def identity[T](x: T): T = x     // generic
```

## Call Syntax

```jo
greet("Jo")                       // parenthesized
greet "Jo"                        // space-separated
gcd 10 15                         // multiple arguments
println                           // no arguments
  "result = " + result            // indented argument
```

## Control Flow

```jo
// if expression
if x > 0 then "positive" else "negative"

// multiline if
if condition then
  doSomething()
else
  doOther()
end

// while loop
while x > 0 do
  x = x - 1

// for loop
for x in list do println x
for x in list if x > 0 do println x
```

## Match

```jo
match expr
  case Var x => x
  case Abs x body => "(\\" + x + "." + (show body) + ")"
  case App f arg => (show f) + " " + (show arg)

// with guards
match n
  case x if x > 0 => "positive"
  case _ => "non-positive"
```

## Classes

```jo
class Point(x: Int, y: Int)          // with parameters

class Rectangle                       // explicit constructor
  val w: Int
  val h: Int
  def Rectangle(width: Int, height: Int) =
    w = width
    h = height
end

class Counter                         // with body
  var count: Int = 0
  def increment(): Unit = count = count + 1
end

class Box[T](value: T)               // generic
```

## Objects

```jo
object None                           // singleton

object NullLogger                     // implements interface
  def log(msg: String): Unit = pass
  view Logger
end
```

## Unions

```jo
union Color = Red | Green | Blue      // simple enum

union Option[T] = Some(value: T) | None

union List[T] = Cons(head: T, tail: List[T]) | Nil
  def isEmpty: Bool = this is Nil     // with methods
end
```

## Interfaces & Views

```jo
// interface
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

// class implements interface via view
class RangeIterator(start: Int, ends: Int)
  var current: Int = start
  def hasNext(): Bool = current < ends
  def next(): Int =
    val value = current
    current = current + 1
    value
  view Iterator[Int]              // RangeIterator <: Iterator[Int]
end

// delegate view
class Service(logger: Logger)
  view Logger = logger            // delegates Logger methods to field
end
```

## Pattern Matching (Advanced)

```jo
// pattern definition
pattern Positive: Partial[Int] = case x if x > 0
pattern Even: Partial[Int] = case x if x % 2 == 0

// composition: & (and), | (or), ! (not)
match n
  case Positive & Even => "positive even"
  case !Positive => "non-positive"

// extracting pattern
pattern Name(name: String): Student =
  case s then name = s.name

// is expression (pattern test with flow typing)
if x is Some(value) then println value
val valid = x is Some(v) && v > 0
while list is [head, ..tail] do
  println head
  list = tail

// sequence patterns
match list
  case [] => "empty"
  case [x] => "one"
  case [first, ..middle, last] => "many"
  case [..positives while Positive, ..rest] => "guarded repeat"

// custom infix pattern
pattern (head: T) :: [T](tail: List[T]): Partial[List[T]] =
  case [head, ..tail]
```

## Collections

```jo
[1, 2, 3]                        // List
[..prefix, 4, 5, ..suffix]       // List with splicing
{"a": 1, "b": 2}                 // Map
{1, 2, 3}                        // Set
```

## Lambdas

```jo
x => x + 1
(x, y) => x + y
(x: Int) => x * 2
() => 42
```

## Type Aliases

```jo
// type alias
type Name = String
type Result[T] = Option[T]

// function type
type Transform = Int => String
type BinOp = (Int, Int) => Int
```

## Union Types

```jo
type Shape = Circle | Rectangle
type Result[T] = Ok(value: T) | Err(msg: String)

// pattern match on union
match shape
  case c: Circle => "circle"
  case r: Rectangle => "rectangle"
```

## Duck Types

```jo
// adapt via member (.toString calls x.toString)
type Printable = like String with [.toString]

// adapt via function (intToStr converts Int to String)
type NumStr = like String with [intToStr, .toString]

// usage: accepts any type that can be adapted to String
def log(msg: Printable): Unit = println msg
log 42                            // 42.toString applied automatically
```

## Extension Types

```jo
// define an extension
extension ListOps[T](xs: List[T])
  def isEmpty: Bool = xs is []
  def head: T = match xs case [x, .._] => x
end

// extension type
type RichList[T] = extend List[T] with ListOps
```

## Typeclasses and Auto Parameters

```jo
// define a typeclass as a type alias
type Eq[T] = (T, T) => Bool

// auto parameter with member candidate
def contains[T](xs: List[T], x: T)(auto eq: Eq[T] with [[T].==]): Bool =
  match xs
    case [] => false
    case [head, ..tail] => eq(head, x) || contains(tail, x)

contains([1, 2, 3], 2)           // auto-resolves [Int].==

// local auto override
auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10
contains([1, 11, 21], 1)         // uses customEq
```

## Context Parameters

```jo
// declare with default
param indent: Int = 0
param logger: Logger = ConsoleLogger

// use in functions (accessed by name)
def line(text: String): Unit = print(" " * indent + text)

// provide at call site
line("hello") with indent = 4
foo() with alpha = 3, beta = 6
```

## Capabilities

```jo
// declare required capabilities
def readFile(path: String): String receives IO.open = ...
def pureFunction(x: Int): Int receives none = ...       // pure

// control capabilities
allow IO.stdout in baz()
allow none in test()              // prove no effects
```

## Namespace & Imports

```jo
namespace app.data

import lib.List
import lib.Map as HashMap
```

## Sections

```jo
section MathUtils
  def abs(x: Int): Int = if x < 0 then -x else x
  def max(a: Int, b: Int): Int = if a > b then a else b
end

MathUtils.abs(-5)                 // qualified access
```

## Visibility

```jo
private def helper() = ...        // private to enclosing scope
private[App] def internal() = ... // private to App
```

## See Also

- [Language Tour](language-tour.md) - Narrative introduction with explanations
- [Get Started](get-started.md) - Install and run Jo
- [Syntax Summary](../language/syntax-summary.md) - Formal grammar
