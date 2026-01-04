# Types

_Draft: Work in Progress_

Jo features a rich type system that supports both functional and object-oriented programming paradigms.

## Basic Types

Beyond primitive types such as Bool and Int, Jo provides:

```jo
class Person(name: String, age: Int)
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

## Lambda Types

Lambda types are primitive types that specify the signature of lambdas, including parameter types, return types, and effect requirements:

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type Callback = () => Unit receives logger
type Predicate[T] = T => Bool
```

Lambda types support **context parameters** (effect parameters) specified with the `receives` clause. These parameters are provided at the call site, not captured when the lambda is created:

```jo
// Lambda type with context parameter
type Logger = String => Unit receives IO.stdout

// Lambda created without IO.stdout
val log: Logger = msg => println("[LOG] " + msg)

// Context parameter comes from call site
log("message")  // Uses ambient IO.stdout
log("message") with IO.stdout = customOutput  // Uses customOutput
```

## Class Types

Class types define structured data with constructors and methods:

```jo
class Config(host: String, port: Int, timeout: Int)

val config = Config("localhost", 8080, 30)
```

Classes can also have methods and mutable fields:

```jo
class Counter
  var count: Int

  def Counter(initial: Int) =
    this.count = initial

  def increment() =
    this.count = this.count + 1

  def get() = this.count
end
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

### Restrictions on Union Types

**Numeric Type Restriction**: A union type cannot contain multiple numeric types (Int, Byte, Char, Double).

```jo
// ❌ Invalid - multiple numeric types
type BadUnion = Int | Double      // Compile error
type BadUnion2 = Char | Byte      // Compile error

// ✓ Valid - single numeric type
type GoodUnion = Double | String  // OK
type GoodUnion2 = Int | List[T]   // OK (if Int were a class)

// ✓ Use tagged unions instead for multiple numeric variants
union NumericValue =
  IntValue(n: Int) |
  DoubleValue(d: Double)
```

Currently, Int, Char, and Byte are primitive types and cannot appear in union types at all. Double is a class type and can appear in union types, but only one numeric type is allowed per union.

**Union definitions**: Jo provides the `union` keyword as syntactic sugar for defining union types. Union definitions automatically generate the necessary classes, type aliases, constructor functions, and patterns. See [Algebraic Data Types](adt.md) for details.

## Generic Types

Jo supports parametric polymorphism through generic types:

```jo
union Either[L, R] = Left(value: L) | Right(value: R)

class Pair[A, B](first: A, second: B)

type Transform[T, R] = T => R
```

## Type Aliases

Type aliases create alternative names for existing types:

```jo
type UserId = Int
type UserName = String
type ConnectionString = String
type EventHandler[T] = T => Unit receives logger
```

## View Types

View types extend existing types with additional interfaces or capabilities using adapters, without modifying the original type definitions:

```jo
// Extend Point with Drawable and Serializable interfaces
type RichPoint = view Point as
  Drawable with pointToDrawable,
  Serializable with pointToSerializable

// Now RichPoint has methods from Point, Drawable, and Serializable
def process(p: RichPoint): Unit =
  p.draw()         // From Drawable
  p.serialize()    // From Serializable
  p.distance()     // From Point
```

View types provide explicit, type-directed extension while maintaining local reasoning. For details, see [View Types](../design/view-types.md).

## Duck Types

Duck types enable parameters to accept arguments that can be automatically converted to a target type through a specified set of adapter functions:

```jo
// StringLike accepts String directly, or converts from Int, Char, etc.
type StringLike = like String with [intToStr, charToStr, .toString]

def println(s: StringLike): Unit = ...

// All of these work:
println("hello")   // Direct String
println(42)        // Converts via intToStr
println('x')       // Converts via charToStr
```

Duck types eliminate repetitive conversion logic and support flexible, reusable APIs. For details, see [Duck Types](../design/duck-types.md).

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
