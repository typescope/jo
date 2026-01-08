# Definitions

_Draft: Work in Progress_

Jo provides various forms of definitions for organizing code and declaring program elements.

## Definition Categories

### [Function Definitions](function-definitions.md)
Define functions with parameters, return types, and effects.

### [Value Definitions](value-definitions.md)
Define immutable (`val`) and mutable (`var`) values.

### [Case Definitions](case-definitions.md)
Destructure values using patterns to bind multiple variables.

### [Pattern Definitions](pattern-definitions.md)
Define reusable patterns for matching.

### [Type Definitions](type-definitions.md)
Create type aliases and define new types.

### [Context Parameters](context-parameters.md)
Define context parameters for capability-based security and dependency injection.

### [Section Definitions](section-definitions.md)
Organize related definitions into sections.

### [Deferred Functions](deferred-functions.md)
Declare functions to be linked at compile time.

### [Interface Definitions](interface-definitions.md)
Define behavioral contracts with method declarations.

### [Class Definitions](class-definitions.md)
Define structured data types with fields and methods.

### [Object Definitions](object-definitions.md)
Define singleton values that implement interfaces or participate in union types.

### [Algebraic Data Types](adt.md)
Define union types with multiple branches.

### [Alias Definitions](alias-definitions.md)
Create aliases for functions, patterns, and parameters.

## Quick Examples

```jo
// Function definition
def greet(name: String): String = "Hello, " + name

// Value definitions
val immutable = 42
var mutable = "can change"

// Case definition
case Point(x, y) = getPoint()

// Pattern definition
pattern Positive: Int = case x if x > 0

// Type definition
type UserId = Int

// Context parameter
param logger: Logger

// Section definition
section Utils
  def helper(): Unit = ...
end

// Deferred function
defer def authenticate(token: String): User

// Interface definition
interface Logger
  def log(message: String): Unit
end

// Class definition
class Point(x: Int, y: Int)

// Object definition
object ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger
end

// Union definition
union Option[T] = Some(value: T) | None

// Alias definition
alias def println = IO.stdout.println
```

## See Also

- [Syntax Summary](../syntax-summary.md) - Complete grammar
- [Types](../types/overview.md) - Type system overview
- [Expressions](../expressions/overview.md) - Expression syntax
