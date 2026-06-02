# Lambdas

`(lambda_param_section | name) "=>" block`

Lambdas are anonymous functions that can be passed as values.

The single-`name` form and `lambda_param_section` (parenthesised parameter list with optional type annotations) are both supported.

## Basic Lambdas

```jo
x => x + 1              // single parameter, type inferred
(x, y) => x + y        // multiple parameters, types inferred
() => 42               // zero parameters
(x: Int) => x * 2      // with type annotation
(x: Int, y: Int) => x + y  // multiple parameters with annotations
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
with IO.stdout = customOutput in logger.log("test")
```

## Lambda Closures

Lambdas capture values from the enclosing scope at creation time:

```jo
def makeAdder(n: Int): Int => Int = x => x + n

val addFive = makeAdder(5)
addFive(3)  // 8 — n = 5 captured from the completed makeAdder call
```

## See Also

- [Expression Forms](expression-forms.md) — Where lambdas fit in the expression grammar
- [Lambda Types](../types/lambda-types.md) — Type system for lambdas
- [Syntax Summary](../syntax-summary.md) — Complete grammar
