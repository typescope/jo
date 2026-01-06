# Alias Definitions

Alias definitions create alternative names for existing functions, patterns, parameters, and other definitions, enabling shorter names and namespace management.

## Syntax

```jo
alias def aliasName = qualifiedFunctionName
alias pattern aliasName = qualifiedPatternName
alias param aliasName = qualifiedParamName
auto alias def aliasName = qualifiedFunctionName
```

## Function Aliases

Create shorter or more convenient names for functions:

```jo
// Alias for operators
alias def + = jo.Int.+
alias def && = jo.Bool.&&
alias def * = jo.Int.*

// Alias for utility functions
alias def println = IO.stdout.println
alias def readLine = IO.stdin.readLine
alias def readFile = FileSystem.read

// Usage
println("Hello, World!")
val line = readLine()
val content = readFile("data.txt")
```

## Pattern Aliases

Create aliases for pattern definitions:

```jo
// Alias for patterns
alias pattern Some = jo.Option.Some
alias pattern None = jo.Option.None
alias pattern Ok = jo.Result.Ok
alias pattern Err = jo.Result.Err

// Usage
match maybeValue
case Some(x) => println(x)
case None => println("No value")
end
```

## Parameter Aliases

Create aliases for context parameters:

```jo
// Alias for parameters
alias param logger = system.Logger
alias param database = system.Database
alias param config = system.Config

// Usage in functions
def process(data: Data): Result receives logger, database =
  logger.info("Processing data")
  database.save(data)
  Ok(data)
```

## Auto Aliases

Auto aliases enable automatic resolution for type-directed features:

```jo
// Auto alias for automatic resolution
auto alias def intEq = jo.Eq.Defaults.intEq
auto alias def stringEq = jo.Eq.Defaults.stringEq
auto alias def listEq = jo.Eq.Defaults.listEq

// Automatically used when needed
val equal = (42 == 42)  // Uses intEq automatically
```

## Importing with Aliases

Create local aliases when importing:

```jo
import Database as DB
import UserManagement as Users

// Use shorter names
val conn = DB.connect()
val user = Users.findUser(42)
```

## Use Cases

### Shortening Qualified Names

```jo
// Long qualified names
val result = MyApp.Utils.StringUtils.trim("  hello  ")

// Create alias
alias def trim = MyApp.Utils.StringUtils.trim

// Use shorter name
val result = trim("  hello  ")
```

### Namespace Management

```jo
section MyApp
  alias def log = ExternalLogger.log
  alias def save = DatabaseLayer.save
  alias def validate = ValidationUtils.validate

  def process(data: Data): Result =
    log("Starting processing")
    if validate(data) then
      save(data)
      Ok(data)
    else
      Err("Invalid data")
end
```

### Compatibility Layers

```jo
// Maintain compatibility with old API
alias def oldFunctionName = newFunctionName
alias pattern OldPattern = NewPattern

// Old code still works
val result = oldFunctionName(arg)

match value
case OldPattern(x) => process(x)
end
```

### Standard Library Imports

```jo
section App
  // Import commonly used functions
  alias def println = IO.stdout.println
  alias def readLine = IO.stdin.readLine

  // Import common patterns
  alias pattern Some = Option.Some
  alias pattern None = Option.None
  alias pattern Ok = Result.Ok
  alias pattern Err = Result.Err

  // Import operators
  alias def ++ = List.concat
  alias def :: = List.cons

  // Now use without qualification
  def main(): Unit receives IO.stdin, IO.stdout =
    println("Enter your name:")
    val name = readLine()
    println("Hello, " + name)
end
```

## Examples

### Application Setup

```jo
section App
  // Standard library aliases
  alias def println = IO.stdout.println
  alias def print = IO.stdout.print
  alias def readLine = IO.stdin.readLine

  // Pattern aliases
  alias pattern Some = Option.Some
  alias pattern None = Option.None

  // Context parameter aliases
  alias param logger = System.Logger
  alias param db = System.Database

  def run(): Unit receives IO.stdout, IO.stdin, logger, db =
    println("Application started")
    logger.info("System initialized")

    print("Enter command: ")
    val cmd = readLine()

    match parseCommand(cmd)
    case Some(command) => executeCommand(command)
    case None => println("Invalid command")
    end
end
```

### Testing Utilities

```jo
section TestUtils
  // Alias assertion functions
  alias def assertEqual = Testing.assertEqual
  alias def assertTrue = Testing.assertTrue
  alias def assertFalse = Testing.assertFalse

  // Create test-specific aliases
  alias param testDB = Testing.MockDatabase
  alias param testLogger = Testing.MockLogger

  def testUserCreation(): Unit receives testDB, testLogger =
    val user = createUser("Alice", "alice@example.com")
    assertEqual(user.name, "Alice")
    assertTrue(testDB.contains(user.id))
end
```

### DSL Construction

```jo
section QueryDSL
  // Alias for query builder functions
  alias def select = QueryBuilder.select
  alias def from = QueryBuilder.from
  alias def where = QueryBuilder.where
  alias def orderBy = QueryBuilder.orderBy

  // Build queries with clean syntax
  def findActiveUsers(): Query =
    select("*")
      .from("users")
      .where("active = true")
      .orderBy("created_at")
end
```

## Auto Aliases and Type Classes

Auto aliases enable automatic resolution for type-directed programming:

```jo
// Define type class
interface Eq[T]
  def equals(x: T, y: T): Bool
end

// Implementations
section Eq.Defaults
  def intEq: Eq[Int] = ...
  def stringEq: Eq[String] = ...
  def boolEq: Eq[Bool] = ...
end

// Auto aliases for automatic resolution
auto alias def intEq = Eq.Defaults.intEq
auto alias def stringEq = Eq.Defaults.stringEq
auto alias def boolEq = Eq.Defaults.boolEq

// Automatically used
def contains[T](list: List[T], item: T): Bool auto eq: Eq[T] =
  match list
  case [] => false
  case [head, ..tail] =>
    if eq.equals(head, item) then true
    else contains(tail, item)
  end
```

## Best Practices

### Clear Naming

```jo
// ✓ Good - clear what it refers to
alias def trim = StringUtils.trim
alias def parse = JsonParser.parse

// ⚠ Less clear
alias def t = StringUtils.trim
alias def p = JsonParser.parse
```

### Group Related Aliases

```jo
section App
  // I/O aliases
  alias def println = IO.stdout.println
  alias def print = IO.stdout.print
  alias def readLine = IO.stdin.readLine

  // Pattern aliases
  alias pattern Some = Option.Some
  alias pattern None = Option.None
end
```

### Document Purpose

```jo
// Alias for backward compatibility with v1.x API
alias def processData = DataProcessor.v2.process

// Shorter name for commonly used function
alias def log = Logger.Defaults.consoleLogger.log
```

## See Also

- [Function Definitions](function-definitions.md) - Defining functions to alias
- [Pattern Definitions](../patterns/pattern-definitions.md) - Defining patterns to alias
- [Context Parameters](context-parameters.md) - Parameters to alias
