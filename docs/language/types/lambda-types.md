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

### Key Differences from Closure Capture

Context parameters differ from traditional closure capture:

1. **Closure capture** - Values captured when lambda is created:
```jo
val prefix = "[INFO] "
val logger = msg => println(prefix + msg)  // prefix captured here
logger("test")  // Uses captured prefix
```

2. **Context parameters** - Values provided when lambda is called:
```jo
type Logger = String => Unit receives IO.stdout
val logger: Logger = msg => println(msg)  // IO.stdout NOT captured
logger("test")  // IO.stdout provided here from calling context
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

Context parameters in lambda interfaces come from the call site:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)
logger.log("test") with IO.stdout = customOutput
```

## Effect Tracking

Lambda types track effects through the `receives` clause:

```jo
// Pure function - no effects
type PureFunction = Int => Int

// Function with IO effects
type IOFunction = String => String receives IO

// Function with multiple effects
type ComplexFunction = Int => String receives IO, logger

// Function with generic effects
type EffectfulFunction[E] = Int => String receives E
```

## Type Inference

Lambda types are often inferred from context:

```jo
// Type inferred from usage
val double = x => x * 2  // Inferred as Int => Int

// Type inferred from parameter
def process(f: String => Int): Unit =
  val result = f("42")  // f has type String => Int

// Explicit type when needed
val parse: String => Int = s => s.toInt
```

## See Also

- [Effect Types](effect-types.md) - For detailed information on the `receives` clause
- [Interface Definitions](../definitions/interface-definitions.md) - For lambda interface adaptation
- [Duck Types](duck-types.md) - For flexible parameter conversion
