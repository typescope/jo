# Effect Types and Context Parameters

Jo's type system tracks computational effects and context dependencies through the `receives` clause. The type of a method includes both its parameters and the context parameters it requires.

## Overview

Effect types enable:

- **Effect tracking**: Know what side effects a function performs
- **Context parameters**: Pass implicit capabilities and resources
- **Fine-grained control**: Specify exactly what effects are allowed
- **Type safety**: Prevent unauthorized access to capabilities

## Syntax

```jo
def functionName(params): ReturnType receives effect1, effect2, ... =
  body
```

## Pure Functions

Functions without effects or context parameters:

```jo
// Pure function - no effects or context
def add(x: Int, y: Int): Int = x + y

def square(x: Int): Int = x * x

def max(a: Int, b: Int): Int =
  if a > b then a else b
```

## Functions with I/O Effects

Functions that perform I/O declare their effects:

```jo
// Function with I/O effects
def readFile(path: String): String receives open =
  File.read(path)

def writeFile(path: String, content: String): Unit receives open =
  File.write(path, content)

def println(message: String): Unit receives IO.stdout =
  IO.stdout.println(message)
```

## Context Parameters

Functions can require context parameters that provide capabilities:

```jo
// Context parameter definition
param config: Config

// Function using context parameter
def createConnection(): Connection receives config =
  Database.connect(config.url, config.timeout)

// Usage - config is automatically provided from ambient context
val conn = createConnection()

// Or explicitly override
val conn = createConnection() with config = customConfig
```

## Multiple Effects and Context

Functions can require multiple effects and context parameters:

```jo
def processRequest(req: Request): Response receives open, logger, validator =
  logger.info("Processing request")
  val data = File.read(req.filePath)  // Uses 'open'
  if validator.isValid(data) then
    logger.info("Data is valid")
    Ok(data)
  else
    logger.error("Invalid data")
    Error("Validation failed")
```

## Generic Functions with Context

Functions can be generic over both types and effects:

```jo
def processData[T](data: T): Result[T] receives logger, validator =
  if validator.isValid(data) then
    logger.info("Data is valid")
    Ok(data)
  else
    logger.error("Invalid data")
    Error("Validation failed")

def withLogging[T, R](operation: T => R, input: T): R receives logger =
  logger.info("Starting operation")
  val result = operation(input)
  logger.info("Operation complete")
  result
```

## Effect Polymorphism

Functions can abstract over effects:

```jo
// Accept any function, preserving its effects
def retry[T, E](operation: () => T receives E, times: Int): T receives E =
  match tryOperation(operation)
  case Ok(result) => result
  case Err(_) if times > 1 => retry(operation, times - 1)
  case Err(error) => throw error
  end
```

## Lambda Types with Effects

Lambda types can specify required effects:

```jo
// Lambda type with no effects
type PureFunction = Int => Int

// Lambda type with IO effects
type IOFunction = String => String receives IO

// Lambda type with context parameters
type LoggingFunction = String => Unit receives logger

// Lambda type with multiple effects
type ComplexFunction = Int => String receives IO, logger, validator
```

## Effect Checking

The type system ensures functions only use declared effects:

```jo
// ✓ OK - declares 'open' effect
def readConfig(): Config receives open =
  File.read("config.json")

// ❌ Error - uses 'open' without declaring it
def readConfig(): Config =
  File.read("config.json")  // Type error: missing 'open' effect

// ✓ OK - caller declares 'open'
def loadData(): Data receives open =
  val config = readConfig()  // OK - 'open' is available
  // ...
```

## Capability-Based Security

Effects enable capability-based security through context parameters:

```jo
// Define capabilities
param fileSystem: FileSystem
param network: Network
param database: Database

// Function requiring specific capabilities
def syncData(): Unit receives fileSystem, network, database =
  val local = fileSystem.read("data.json")
  val remote = network.fetch("https://api.example.com/data")
  database.update(local, remote)

// Cannot call syncData without providing capabilities
// syncData()  // Error - missing capabilities

// Must explicitly provide capabilities
syncData() with
  fileSystem = limitedFS,
  network = restrictedNet,
  database = readOnlyDB
```

## Effect Inference

Effect requirements are inferred when not explicitly specified:

```jo
// Effects inferred from body
def process(path: String) =
  val content = File.read(path)  // Uses 'open'
  println(content)                // Uses 'IO.stdout'
  // Inferred type: String => Unit receives open, IO.stdout

// Can also be explicit
def process(path: String): Unit receives open, IO.stdout =
  val content = File.read(path)
  println(content)
```

## Built-in Effects

Jo provides standard effects:

- **`IO`** - General I/O operations
- **`IO.stdout`** - Standard output
- **`IO.stdin`** - Standard input
- **`IO.stderr`** - Standard error
- **`open`** - File system access
- **`network`** - Network access
- **`time`** - System time access

## See Also

- [Context Parameter Definitions](../definitions/context-parameters.md) - For defining context parameters
- [Lambda Types](lambda-types.md) - For lambda types with effects
- [Capability-Based Security](../../overview/capabilities/index.html) - For security model
- For detailed design, see [Context Parameters](../../design/context-parameters.md)
