# Function Definitions

Function definitions declare reusable computations with parameters, return types, and effect requirements.

## Basic Functions

Simple function with explicit types:

```jo
def greet(name: String): String = "Hello, " + name

def add(x: Int, y: Int): Int = x + y

def square(n: Int): Int = n * n
```

## Nullary Functions

At definition-site, empty `()` is optional:

```jo
// These are equivalent
def answer: Int = 42
def answer2(): Int = 42

// Unit-returning nullary function
def log(): Unit = println("ok")
```

If `()` is omitted, the compiler behaves as if `()` were inserted automatically.

At call-site, empty `()` is automatically inserted for nullary calls when the
function result type is not `Unit`:

```jo
def answer: Int = 42
def answer2(): Int = 42
def log(): Unit = println("ok")

val a = answer      // treated as answer()
val b = answer2     // treated as answer2()

log()               // required
```

::: info Why Mandatory () for Unit-returning functions

Requiring `()` for `Unit`-returning nullary functions makes effectful
calls explicit.

For non-`Unit` nullary functions, using explicit `()` is still encouraged
when the call is effectful or expensive.

A stricter language rule is hard to define without hurting usability:
whether a call is effectful or expensive is often context-dependent.
Requiring function authors to lock this into each function contract can
cause choice paralysis for borderline cases which are common, and can
frustrate end-users with rigid call syntax rules for low-value distinctions.
:::
## Functions with Effects

Functions can declare effect requirements using the `receives` clause:

```jo
def printMessage(message: String): Unit receives IO.stdout =
  println(message)

def readFile(path: String): String receives open =
  File.read(path)

def process(data: List[Int]): Unit receives logger =
  logger.info("Processing started")
  // ... processing logic
```

The `receives` clause is optional and will be inferred if left unspecified.

## Functions with Context Parameters

Functions can require context parameters for capabilities and dependencies:

```jo
// Define context parameter
param config: Config

// Use context parameter
def createConnection(): Connection receives config =
  Database.connect(config.url, config.timeout)

// Multiple context parameters
def processRequest(req: Request): Response receives logger, validator, database =
  logger.info("Processing request")
  val data = database.fetch(req.id)
  if validator.isValid(data) then
    Ok(data)
  else
    Error("Invalid")
```

## Generic Functions

Functions can have type parameters:

```jo
def identity[T](x: T): T = x

def map[T, R](list: List[T], f: T => R): List[R] =
  match list
  case [] => []
  case [head, ..tail] => [f(head), ..map(tail, f)]
  end

def filter[T](list: List[T], pred: T => Bool): List[T] =
  match list
  case [] => []
  case [head, ..tail] if pred(head) =>
    [head, ..filter(tail, pred)]
  case [_, ..tail] =>
    filter(tail, pred)
  end
```

## Operator Functions

An operator function can be defined by using an operator as the function name:

```jo
class Complex(real: Int, image: Int)

def (c1: Complex) + (c2: Complex): Complex = ...
def (c1: Complex) - (c2: Complex): Complex = ...
def (c1: Complex) * (c2: Complex): Complex = ...
```

An operator function may have at most 1 pre/post parameters. Postfix operators
that only have pre-parameters are not supported.

Precedence and associativity of operators often confuse programmers. Jo intentionally disallows specifying precedence and associativity:

- All operators are left-associative.
- Precedence is only defined for the familiar numeric and Boolean infix operators.

All other operators have the same precedence.

::: info Precedence and Associativity

Precedence and associativity are useful mathematical and programming
conventions. However, custom operators with arbitrary precedence and
associativity will undermine the convention and greatly harm readability
of code.

Therefore, Jo respects and protects that convention by defending against
custom operators with arbitrary precedence and associativity. When in doubt,
programmers can always make the code structure more clear and readable.
:::
## Default Parameter Values

Parameters can carry default values. When an explicit call (not parentheses-less infix call) omits trailing arguments the compiler inserts the defaults automatically.

```jo
def greet(name: String, greeting: String = "Hello"): String =
  greeting + ", " + name + "!"

greet("World")          // "Hello, World!"
greet("World", "Hi")    // "Hi, World!"
```

Multiple trailing parameters may have defaults; the caller can omit any suffix of them:

```jo
def connect(host: String, port: Int = 5432, timeout: Int = 30): Connection =
  Database.open(host, port, timeout)

connect("localhost")            // port = 5432, timeout = 30
connect("localhost", 5433)      // timeout = 30
connect("localhost", 5433, 60)  // all explicit
```

### Constraints

**Trailing suffix** — once a parameter has a default, every subsequent parameter in the same section must also have a default:

```jo
def foo(x: Int = 1, y: Int): Int = x + y   // ❌ error: y must have a default
```

**Allowed default expressions** — a default must be a literal or a qualified identifier that refers to a top-level value or a top-level parameterless, non-polymorphic function with no auto parameters:

```jo
def LIMIT = 100

def take(xs: List[Int], n: Int = LIMIT): List[Int] = ...  // ✓ qualid default
def add(x: Int, y: Int = 1 + 2): Int = x + y             // ❌ error: expression not allowed

def outer(): Int =
  def localHelper = 1
  def inner(x: Int = localHelper): Int = x  // ❌ error: localHelper is not top-level
  inner()
```

**No vararg default** — a vararg parameter (`..`) cannot have a default value.

**Scope** — Class parameters, pattern parameters, and union branch parameters do not support defaults.

## Return Type Inference

Return types can be inferred for non-recursive functions:

```jo
// Return type inferred as Int
def square(x: Int) = x * x

// Return type inferred as String
def greet(name: String) = "Hello, " + name
```

Recursive functions must have explicit return types:

```jo
// ✓ OK - explicit return type
def factorial(n: Int): Int =
  if n <= 1 then 1
  else n * factorial(n - 1)

// ❌ Error - recursive function needs explicit return type
def factorial(n: Int) =
  if n <= 1 then 1
  else n * factorial(n - 1)
```

## Return Values

### Implicit Return

Jo is expression-oriented. The value of the last expression in a function body is
its return value — no `return` keyword is needed:

```jo
def add(x: Int, y: Int): Int =
  val sum = x + y
  sum           // implicitly returned

def classify(n: Int): String =
  if n < 0 then "negative"
  else if n == 0 then "zero"
  else "positive"
```

### Explicit Return

The `return` keyword exits a function early with a given value:

```jo
def repeat(n: Int): String =
  if n <= 0 then return ""
  "*" + repeat(n - 1)
```

`return` without an argument returns `Unit`, useful for early exit in void functions:

```jo
def printPositive(n: Int): Unit =
  if n <= 0 then return
  println(n)
```

### Rules

**Explicit return type required.** A function that uses `return` must have an explicit
return type annotation. Return type inference does not work across `return` sites:

```jo
def repeat(n: Int): String =   // ✓ explicit return type
  if n <= 0 then return ""
  "*" + repeat(n - 1)

def repeat(n: Int) =           // ❌ error: explicit return type required with return
  if n <= 0 then return ""
  "*" + repeat(n - 1)
```

**No non-local returns.** `return` exits the immediately enclosing named function.
Using `return` inside a lambda is a compile error:

```jo
def findFirst(xs: List[Int], pred: Int => Bool): Int =
  xs.each(x =>
    if pred(x) then return x   // ❌ error: return not allowed in lambda
  )
  -1
```

**Return expression must conform to the return type.** The value given to `return`
is checked against the function's declared return type, the same as any other return
path:

```jo
def f(n: Int): String =
  if n < 0 then return n   // ❌ error: Int does not conform to String
  n.toString
```

## Varargs

For auto parameters, see [Varargs](varargs.md).

## Auto Parameters

For auto parameters, see [Auto Parameters](autos.md).

## See Also

- [Context Parameters](../concepts/context-parameters.md) - For context parameter definitions
