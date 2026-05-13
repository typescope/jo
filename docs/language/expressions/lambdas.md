# Lambdas

`(param_section | name) "=>" block`

Lambdas are anonymous functions that can be passed as values.

## Basic Lambdas

```jo
x => x + 1              // single parameter
(x, y) => x + y        // multiple parameters
() => 42               // zero parameters
(x: Int) => x * 2      // with type annotation
```

When the body fits on one line, the lambda is written inline. When it needs multiple phrases, the `=>` introduces a block:

```jo
val process = (x: Int) =>
  val doubled = x * 2
  doubled + 1

val classify = n =>
  if n > 0 then "positive"
  else if n < 0 then "negative"
  else "zero"
```

An inline lambda is a closed expression and can appear as a call argument. A block-body lambda is an open expression:

```jo
// Inline lambda as call argument
list.map(x => x * 2)

// Block-body lambda as a phrase
val compute = x =>
  val a = step1(x)
  step2(a)
```

## Lambda Interfaces

Lambdas automatically adapt to interface types with a single abstract method (SAM):

```jo
interface Predicate[T]
  def test(x: T): Bool
end

val isEven: Predicate[Int] = x => x % 2 == 0

isEven.test(4)  // true
```

When a lambda is assigned to a SAM interface type, it automatically creates an implementation. The lambda body becomes the implementation of the abstract method.

## Lambda Context Parameters

Context parameters in lambda interfaces are provided at the call site, not captured in the closure:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)

// Context parameter provided at call site
logger.log("test") with IO.stdout = customOutput
```

## Lambda Closures

Lambdas capture variables from their surrounding scope:

```jo
val multiplier = 10
val timesX = (x: Int) => x * multiplier

timesX(5)  // 50
```

Captured variables are stored in the lambda's closure and remain accessible even after the surrounding scope exits.

## See Also

- [Expression Forms](expression-forms.md) — Where lambdas fit in the expression grammar
- [Lambda Types](../types/lambda-types.md) — Type system for lambdas
- [Syntax Summary](../syntax-summary.md) — Complete grammar
