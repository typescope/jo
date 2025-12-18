# Types

_Draft: Work in Progress_

Jo features a rich type system that supports both functional and object-oriented programming paradigms.

## Basic Types

Beyond primitive types such as Bool and Int, Jo provides:

```jo
type Person = { name: String, age: Int }
union Option[T] = Some(value: T) | None
union Result[T, E] = Ok(value: T) | Error(error: E)
```

## List Types

Jo standard library implements List type to make daily programming easy. Lists are immutable with efficient O(1) prepend, append, and concat operations:

```jo
// Usage examples
val empty: List[Int] = []
val numbers = [1, 2, 3]

// Pattern matching with lists
match numbers
case [] => println "Empty list"
case [head, ..tail] => println ("First element: " + head)
```

## Function Types

Function types specify the signature of functions, including parameter types, return types, and effect requirements. Function types desugar to object types with an `apply` method:

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type Callback = () => Unit receives logger

// Function types desugar to object types like:
// type Handler = { def apply(arg1: String, arg2: Int): Unit }
// type Processor = { def apply(arg: String): String receives IO }
```

## Record Types

Record types define structured data with named fields:

```jo
type Config = {
  host: String,
  port: Int,
  timeout: Int
}

val config = { host = "localhost", port = 8080, timeout = 30 }
```

## Union Types

Union types represent values that can be one of several alternatives, specified with `|` separating the branches. Each branch must be either a class type or another union type:

```jo
// Classes for union branches
class Success
class Warning
class Error

// Union type combining classes
type Status = Success | Warning | Error

// Parameterized classes
class Data(content: String)
class ErrorMsg(message: String)
class Empty

// Union type with parameterized branches
type Response = Data | ErrorMsg | Empty

// Generic union types
class None
class Some[T](value: T)
type Option[T] = None | Some[T]

class Left[L](value: L)
class Right[R](value: R)
type Either[L, R] = Left[L] | Right[R]

// Pattern matching with union types
val result: Response = Data("some content")
match result
case Data(content) => println ("Success: " + content)
case ErrorMsg(message) => println ("Error: " + message)
case Empty => println "No data"
```

**Union definitions**: Jo provides the `union` keyword as syntactic sugar for defining union types. Union definitions automatically generate the necessary classes, type aliases, constructor functions, and patterns. See [Algebraic Data Types](adt.md) for details.

## Object Types

Object types define interfaces with methods and fields:

```jo
type Logger = {
  def info(message: String): Unit
  def error(message: String): Unit
  var level: String
}

type Database = {
  def connect(url: String): Connection
  def query(sql: String): ResultSet receives IO
  def close(): Unit
}
```

## Generic Types

Jo supports parametric polymorphism through generic types:

```jo
type Container[T] = {
  def get(): T
  def set(value: T): Unit
}

union Either[L, R] = Left(value: L) | Right(value: R)

type Map[K, V] = {
  def get(key: K): Option[V]
  def put(key: K, value: V): Unit
}
```

## Type Aliases

Type aliases create alternative names for existing types:

```jo
type UserId = Int
type UserName = String
type ConnectionString = String

// More complex aliases
type UserProfile = { id: UserId, name: UserName, email: String }
type EventHandler[T] = T => Unit receives logger
```

## Effect Types and Context Parameters

Jo's type system tracks computational effects and context dependencies through the `receives` clause. The type of a method includes both its parameters and the context parameters it requires:

```jo
// Pure function - no effects or context
def add(x: Int, y: Int): Int = x + y

// Function with I/O effects
def readFile(path: String): String receives open =
  File.read(path)

// Function with context parameters
param config: Config
def createConnection(): Connection receives config =
  Database.connect(config.url, config.timeout)

// Function with multiple effects and context
def processRequest(req: Request): Response receives open, logger, validator =
  logger.info("Processing request")
  val result = database.fetch(req.id)
  if validator.isValid(result) then
    Ok(result)
  else
    logger.error("Invalid result")
    Error("Validation failed")

// Generic function with context
def processData[T](data: T): Result[T] receives logger, validator =
  if validator.isValid(data) then
    logger.info("Data is valid")
    Ok(data)
  else
    logger.error("Invalid data")
    Error("Validation failed")
```

## Type Inference

Jo performs sophisticated type inference, reducing the need for explicit type annotations:

```jo
// Type inferred as List[Int]
val numbers = [1, 2, 3, 4, 5]

// Type inferred as String => Int
val length = s => s.size

// Type inferred as Option[String]
val result = if condition then Some("value") else None
```
