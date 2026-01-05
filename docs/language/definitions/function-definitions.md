# Function Definitions

Function definitions declare reusable computations with parameters, return types, and effect requirements.

## Syntax

```jo
def functionName(param1: Type1, param2: Type2): ReturnType = body

def functionName(params): ReturnType receives effect1, effect2 = body
```

## Basic Functions

Simple function with explicit types:

```jo
def greet(name: String): String = "Hello, " + name

def add(x: Int, y: Int): Int = x + y

def square(n: Int): Int = n * n
```

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

## Function Body

Function bodies can be expressions or blocks:

```jo
// Expression body
def double(x: Int): Int = x * 2

// Block body
def process(data: Data): Result =
  val validated = validate(data)
  if validated then
    val transformed = transform(data)
    Ok(transformed)
  else
    Err("Invalid data")
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

An operator function may have at most 1 pre/post parameters.

Precedence and associativity of operators often confuse programmers. Jo intentionally disallows specifying precedence and associativity:

- All operators are left-associative.
- Precedence is only defined for the familiar numeric and Boolean operators.

All other operators have the same precedence.

!!!info "Precedence and Associativity"

    Precedence and associativity are useful mathematical and programming
    conventions. However, custom operators with arbitrary precedence and
    associativity will undermine the convention and greatly harm readability
    of code.

    Therefore, Jo respects and protects that convention by defending against
    custom operators with arbitrary precedence and associativity. When in doubt,
    programmers can always make the code structure more clear and readable.

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

## Varargs

For auto parameters, see [Varargs](varargs.md).

## Auto Parameters

For auto parameters, see [Auto Parameters](autos.md).

## Examples

```jo
// Simple function
def greet(name: String): String = "Hello, " + name

// Function with effects
def log(message: String): Unit receives IO.stdout =
  println("[LOG] " + message)

// Generic function
def swap[A, B](pair: Pair[A, B]): Pair[B, A] =
  Pair(pair.second, pair.first)

// Function with context parameters
param database: Database
def saveUser(user: User): Unit receives database =
  database.save(user)

// Recursive function
def length[T](list: List[T]): Int =
  match list
  case [] => 0
  case [_, ..tail] => 1 + length(tail)
  end

// Function with complex logic
def processData(data: List[Int]): Result receives logger, validator =
  logger.info("Starting processing")
  val validated = data.filter(x => validator.isValid(x))
  if validated.isEmpty then
    logger.error("No valid data")
    Err("Validation failed")
  else
    logger.info("Processing " + validated.length + " items")
    val result = validated.map(x => x * 2).sum
    Ok(result)
```

## See Also

- [Effect Types](../types/effect-types.md) - For the `receives` clause
- [Context Parameters](context-parameters.md) - For context parameter definitions
