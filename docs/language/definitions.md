# Definitions

Jo provides various forms of definitions for organizing code and declaring program elements. This section covers all the major definition types.

## Function Definitions

```jo
// Simple function
def greet(name: String): String = "Hello, " + name

// Function with effects
def writeFile(path: String, content: String): Unit receives IO.write =
  File.write(path, content)

// Function with context parameters
def process(data: List[Int]): Unit receives logger =
  logger.info("Processing started")
  // ... processing logic
```

## Value Definitions

```jo
val immutable = 42
var mutable = "can change"

// Type annotations
val typed: String = "explicitly typed"
var counter: Int = 0
```

## Pattern Definitions

```jo
pattern Name(name: String): Student =
  case #Student name _ _

pattern Some[T](value: T): #Some(v: T) =
  case #Some value

pattern None: #None = case #None

pattern Student(s: String, sex: Bool, age: Int): Student =
  case #Student s sex age
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

// Algebraic data types
data List[T] = #Nil | #Cons(head: T, tail: List[T])

data Tree[T] =
  | #Leaf(value: T)
  | #Branch(left: Tree[T], right: Tree[T])
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