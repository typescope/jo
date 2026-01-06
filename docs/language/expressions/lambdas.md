# Lambdas

`(param_section | identifier) "=>" block`

Lambdas are anonymous functions that can be passed as values.

## Basic Lambdas

```jo
x => x + 1              // single parameter
(x, y) => x + y        // multiple parameters
() => 42               // zero parameters
(x: Int) => x * 2      // with type annotation
```

## Lambda Interfaces

Lambdas automatically adapt to interface types with a single abstract method:

```jo
interface Predicate[T]
  def test(x: T): Bool
end

val isEven: Predicate[Int] = x => x % 2 == 0

// Use as interface
isEven.test(4)  // true
```

When a lambda is assigned to an interface type with a single abstract method (SAM interface), it automatically creates an implementation of that interface. The lambda body becomes the implementation of the abstract method.

## Lambda Context Parameters

Context parameters in lambda interfaces come from the call site:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)

// Context parameter provided at call site
logger.log("test") with IO.stdout = customOutput
```

When a lambda implements an interface whose abstract method receives context parameters, those parameters are not captured in the lambda closure. Instead, they are provided at the call site when the interface method is invoked.

## Lambda Closures

Lambdas can capture variables from their surrounding scope:

```jo
val multiplier = 10
val timesX = (x: Int) => x * multiplier

timesX(5)  // 50
```

Captured variables are stored in the lambda's closure and remain accessible even after the surrounding scope has exited.

## See Also

- [Words](words.md) - Overview of word forms
- [Lambda Types](../types/lambda-types.md) - Type system for lambdas
- [Syntax Summary](../syntax-summary.md) - Complete grammar
