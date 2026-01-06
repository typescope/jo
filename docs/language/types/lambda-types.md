# Lambda Types

Lambda types are primitive types that specify the signature of lambdas, including parameter types, return types, and effect requirements.

## Syntax

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type Callback = () => Unit receives logger
type Predicate[T] = T => Bool
```

## Basic Lambda Types

Lambda types specify the shape of anonymous functions:

```jo
// Single parameter
type Incrementer = Int => Int

// Multiple parameters
type Adder = (Int, Int) => Int

// No parameters
type Producer = () => String

// Generic lambda type
type Mapper[T, R] = T => R
```

## Context Parameters

Lambda types can specify **context parameters** using the `receives` clause. Context parameters listed in the lambda type's `receives` clause are provided at the call site:

```jo
// Lambda type with context parameter
type Logger = String => Unit receives IO.stdout

// Lambda created without IO.stdout
val log: Logger = msg => println("[LOG] " + msg)

// Context parameter IO.stdout comes from call site
log("message")  // Uses ambient IO.stdout
log("message") with IO.stdout = customOutput  // Uses customOutput
```

Context parameters not listed in the lambda type's `receives` clause are captured at lambda creation time (like normal closure capture):

```jo
// Lambda type does NOT receive logger
type Processor = String => String receives IO

// logger is captured at creation time
val processor: Processor = msg =>
  logger.log("Processing: " + msg)  // logger captured here
  msg.toUpperCase

// IO is provided at call site
processor("hello") with IO = customIO
```

## Lambda Interfaces

Lambdas automatically adapt to interface types with a single abstract method:

```jo
interface Predicate[T]
  def test(x: T): Bool
end

// Lambda adapts to interface
val isEven: Predicate[Int] = x => x % 2 == 0

// Use as interface
if isEven.test(4) then
  println "4 is even"
```

## See Also

- [Interface Definitions](../definitions/interface-definitions.md) - For lambda interface adaptation
- [Duck Types](duck-types.md) - For flexible parameter conversion
