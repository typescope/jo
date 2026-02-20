# Jo Cheat Sheet

A quick reference for Jo syntax.

## Literals

```jo
42                          // Int
3.14                        // Float
1_000_000                   // underscores for readability
0xFF_FF                     // hexadecimal
6.022e23                    // scientific notation
true  false                 // Bool
'a'  '\n'                   // Char
val unicode: Char = 0x1F600 // hex with expected type Char
```

## Comments

```jo
// line comment

//[ block comment //]

///[
  multi-line block comment
  (number of slashes must match)
///]
```

## Strings

```jo
"hello \{name}"                   // interpolation with \{...}
"line1\nline2"                    // escape sequences

// multi-line strings use triple quotes
// Content must start on a new line after opening quotes
val html = """
  <div>hello</div>
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
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

class RangeIterator(start: Int, ends: Int)
  var current: Int = start
  def hasNext(): Bool = current < ends
  def next(): Int =
    val value = current
    current = current + 1
    value
  view Iterator[Int]
end
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
type Name = String
type Result[T] = Option[T]
type Transform = Int => String
type BinOp = (Int, Int) => Int
```

## Pattern Matching (Advanced)

```jo
// is expression (pattern test with flow typing)
if x is Some(value) then println value
val valid = x is Some(v) && v > 0

// sequence patterns
match list
  case [] => "empty"
  case [x] => "one"
  case [first, ..middle, last] => "many"
```

## Context Parameters

```jo
param indent: Int = 0

def line(text: String): Unit = print(" " * indent + text)

line("hello") with indent = 4
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

## Important Notes

- Strings use `\{expr}` for interpolation (not `${}`)
- Multi-line strings: content must start on a new line after `"""`
- No semicolons; indentation-based blocks
- `begin ... end` for multi-statement expressions
- `for x in list do ...` for iteration
- `match expr case ... => ...` for pattern matching
