# Definitions

_Draft: Work in Progress_

Jo provides various forms of definitions for organizing code and declaring program elements. This section covers all the major definition types.

## Function Definitions

```jo
// Simple function
def greet(name: String): String = "Hello, " + name

// Function with effects
def printMessage(message: String): Unit receives stdout =
  println message

// Function with context parameters
def process(data: List[Int]): Unit receives logger =
  logger.info("Processing started")
  // ... processing logic
```

The `receives` clause is optional and will be inferred if left unspecified.

### Auto Parameters

TODO

### Operator Functions

An operator function can be defined by using an operator as the function name:

```jo
class Complex(real: Int, image: Int)

def (c1: Complex) + (c2: Complex): Complex = ...
def (c1: Complex) - (c2: Complex): Complex = ...
def (c1: Complex) * (c2: Complex): Complex = ...
```

An operator function may have at most 1 pre/post parameters.

Precedence and associativity of operators often confuse programmers. Jo
intentially disallow specifying precedence and associativity:

- All operatros are left-associative.
- Precedence is only defined for the familiar numeric and Boolean operators.

All other operators have the same precedence.

!!!info "Precedence and Associativity"

    Precedence and associativity are useful mathematical and programming
    conventions. However, custom operators with arbitrary precedence and
    associativity will undermine the convention and greatly harm readability
    of code.

    Therefore, Jo respects and protects that convention by defending against
    custom operators with arbitrary precedence and associativity. When in doubt,
    programers can always make the code structure more clear and readable.

## Value Definitions

Value definitions can only appear inside function bodies, not at the top level of a namespace or section.

```jo
def example(): Unit =
  val immutable = 42
  var mutable = "can change"

  // Type annotations
  val typed: String = "explicitly typed"
  var counter: Int = 0

  // Modify mutable values
  counter = counter + 1
```

## Pattern Definitions

```jo
pattern Name(name: String): Student =
  case s then name = s.name

pattern Student(s: String, sex: Bool, age: Int): Student =
  case std then s = std.name, sex = std.sex, age = std.age
```

## Type Definitions

```jo
// Basic type alias
type UserId = Int
type Name = String

// Record types
type Config = {
  host: String,
  port: Int,
  timeout: Int
}
```

## Context Parameter Definitions

```jo
// Simple parameter
param logger: Logger

// Parameter with default
param timeout: Int = 30

// Multiple parameters
param database: Database
param cache: Cache
```

## Section Definitions

```jo
section Database
  def connect(url: String): Connection = ...
  def query(sql: String): ResultSet = ...
  def close(conn: Connection): Unit = ...
end

section Validation
  def validateEmail(email: String): Bool = ...
  def validateAge(age: Int): Bool = ...
end
```

## Deferred Function Definitions

```jo
// Deferred function declaration
defer def authenticate(token: String): User

// Usage in other functions
def handleRequest(request: Request): Response =
  val user = authenticate(request.token)
  // ... handle request

// Linked at compile time with -link option
// bin/jo build -link MyApp.authenticate=OAuth.verify app.jo -o app
```

## Interface Definitions

Interfaces define behavioral contracts with method declarations:

```jo
// Simple interface
interface Logger
  def info(message: String): Unit
  def error(message: String): Unit
end

// Generic interface
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

// Interface with concrete methods
interface Comparable[T]
  def compare(x: T, y: T): Int
  def equals(x: T, y: T): Bool = compare(x, y) == 0  // Concrete default
end

// Interface with effects
interface FileSystem
  def readFile(path: String): String receives open
  def writeFile(path: String, content: String): Unit receives open
end
```

Interfaces are implemented through views in classes. For details on how classes implement interfaces and the view mechanism, see [Interfaces and Views](../design/interface-views.md).

## Class Definitions

Classes define new types with fields and methods:

```jo
// Simple class with parameters (automatic constructor)
class Point(x: Int, y: Int)

// Class with explicit constructor
class Person
  val name: String
  val age: Int

  def Person(name: String, age: Int) =
    this.name = name
    this.age = age

  def greet: String = "Hello, I'm " + name
  def isAdult: Bool = age >= 18
end

// Class with parameters and methods (parameters become fields)
class Rectangle(width: Int, height: Int)
  def area: Int = width * height
  def perimeter: Int = 2 * (width + height)
end

// Generic class
class Box[T](value: T)
  def map[U](f: T => U): Box[U] = Box(f(value))
end

// Usage
val p = Point(10, 20)
val person = Person("Alice", 30)
val rect = Rectangle(5, 10)
val box = Box(42)
```

A class with class parameters or with an empty body is considered a **data class**. For data classes, the compiler automatically generates constructor functions and pattern definitions for pattern matching.

## Union Definitions

Union definitions create algebraic data types with multiple branches:

```jo
union Option[T] = Some(value: T) | None
union Result[T, E] = Ok(value: T) | Err(error: E)
union List[T] = Cons(head: T, tail: List[T]) | Nil
union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])

// Usage examples
val some: Option[Int] = Some(20)
val result: Result[Int, String] = Ok(42)
val list: List[Int] = Cons(4, Cons(5, Nil))
val tree: Tree[String] = Leaf("value")
```

For detailed information on algebraic data types, see [Algebraic Data Types](adt.md).

## Alias Definitions

```jo
// Function aliases
alias def + = jo.Int.+
alias def && = jo.Bool.&&

// Pattern aliases
alias pattern Some = jo.Option.Some
alias pattern None = jo.Option.None

// Parameter aliases
alias param logger = system.Logger

// Auto aliases for automatic resolution
auto alias def intEq = jo.Eq.Defaults.intEq
```
