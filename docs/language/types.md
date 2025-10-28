# Types

Jo features a sophisticated type system that supports both functional and object-oriented programming paradigms. This section covers the various type constructs available in the language.

## Basic Types

Jo provides several fundamental types:

```jo
type Person = { name: String, age: Int }
type Option[T] = #Some(value: T) | #None
type Result[T, E] = #Ok(value: T) | #Error(error: E)
```

## Function Types

Function types specify the signature of functions, including parameter types, return types, and effect requirements:

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type Callback = () => Unit receives logger
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

## Algebraic Data Types

Algebraic data types enable the definition of sum types with multiple variants:

```jo
data List[T] = #Nil | #Cons(head: T, tail: List[T])

data Tree[T] = 
  | #Leaf(value: T) 
  | #Branch(left: Tree[T], right: Tree[T])

data Option[T] = #None | #Some(value: T)
```

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

type Either[L, R] = #Left(value: L) | #Right(value: R)

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

## Union Types

Union types allow values to be one of several specified types:

```jo
type NumberOrString = Int | String
type Status = #Success | #Warning | #Error
type Response = #Data(content: String) | #Error(message: String) | #Empty
```

## Subtyping

Jo supports structural subtyping relationships:

```jo
type Animal = {
  def name(): String
  def sound(): String
}

type Dog <: Animal = {
  def name(): String
  def sound(): String
  def breed(): String
}
```

## Effect Types

Jo's type system tracks computational effects through the `receives` clause:

```jo
// Pure function - no effects
def add(x: Int, y: Int): Int = x + y

// Function with I/O effects
def readFile(path: String): String receives IO.read = 
  File.read(path)

// Function with multiple effects
def processRequest(req: Request): Response receives IO, logger =
  logger.info("Processing request")
  val data = database.fetch(req.id)
  Response.success(data)
```

## Type Inference

Jo performs sophisticated type inference, reducing the need for explicit type annotations:

```jo
// Type inferred as List[Int]
val numbers = [1, 2, 3, 4, 5]

// Type inferred as String => Int
val length = s => s.size

// Type inferred as Option[String]
val result = if condition then #Some("value") else #None
```

## Context-Dependent Types

Types can depend on context parameters for flexible, dependency-injected code:

```jo
param config: Config

// Type depends on config parameter
def createConnection(): Connection receives config =
  Database.connect(config.url, config.timeout)

// Generic function with context
def processData[T](data: T): Result[T] receives logger, validator =
  if validator.isValid(data) then
    logger.info("Data is valid")
    #Ok(data)
  else
    logger.error("Invalid data")
    #Error("Validation failed")
```