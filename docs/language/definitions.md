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

## Data Definitions

Data definitions create new data types with a single constructor:

```jo
data Box[T](value: T)
data Post(title: String, author: String, content: String)

// Usage examples
val box = Box(5)
val post = Post("Hello, World", "Turing", "Content here")
```

## Enum Definitions

Enum definitions create algebraic data types with multiple constructors:

```jo
data Option[T] = Some(value: T) | None
data List[T] = Cons(head: T, tail: List[T]) | Nil
data Tree[T] = Node(value: T, left: Tree[T], right: Tree[T]) | Leaf(value: T)
data Either[S, T] = Left(value: S) | Right(value: T)

// Usage examples
val some: Option[Int] = Some(20)
val list: List[Int] = Cons(4, Cons(5, Nil))
val tree: Tree[String] = Leaf("value")
val either: Either[Int, String] = Left(42)
```

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
