# Object Definitions

## Overview

Object definitions provide a convenient syntax for defining singleton values with associated behavior. An object definition combines a value binding with a class definition, ensuring that exactly one instance of the class exists and is accessible through a named identifier.

!!! info "When to Use Sections vs Objects"
    If you need a **stateless** collection of functions that will **not** be used in union types or as interface implementations, prefer **section definitions** instead of objects. Sections provide a simpler mechanism for grouping related functions without creating singleton instances.

    Use objects when you need:

    - A singleton that implements an interface (e.g., `object ConsoleLogger` with `view Logger`)
    - A singleton used in union types
    - Type identity for a stateless singleton

    Use sections when you need:

    - Stateless utility functions (e.g., `MathUtils`)
    - Namespace organization

## Syntax

### Basic Object Definition

```jo
object Logger
  def log(msg: String): Unit = println(msg)
  def error(msg: String): Unit = eprintln(msg)
end
```

### Object with Views

Objects can declare views to expose interfaces:

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

object ConsoleLogger
  def log(msg: String): Unit = println(msg)
  def error(msg: String): Unit = eprintln(msg)

  view Logger
end
```

## Semantics

### Desugaring

An object definition desugars into two declarations:

```jo
object A
  // members...
end
```

Desugars to:

```jo
def A: A = ... // Singleton instance (backend-determined initialization)

pattern A: A = case _

class A
  // members...
end
```

The `def A: A` binding provides access to the singleton instance, while the class definition specifies its structure and behavior.

!!!info "object initialization"

    The exact initialization strategy is determined by the backend. Programmers
    should not observe any semantic difference no matter what strategy is taken.

### Top-Level Restriction

Object definitions must appear at the top level of a namespace, just like class and interface definitions:

```jo
// Valid: top-level object
object Logger
  def log(msg: String): Unit = println(msg)
end

def process(): Unit =
  // Invalid: objects cannot be local
  object LocalLogger  // Error: object definitions must be top-level
    def log(msg: String): Unit = println(msg)
  end
end
```

**Rationale**: Top-level restriction ensures:

- Consistent singleton semantics (not recreation on each function call)
- Clear visibility and accessibility
- Simpler compilation model

### No Type Parameters

Objects cannot have type parameters:

```jo
// Invalid: objects cannot be generic
object Container[T]  // Error: object definitions cannot have type parameters
  def getDefault(): T = ...
end

// Valid: concrete type
object IntUtils
  def abs(x: Int): Int = if x < 0 then -x else x
end
```

**Rationale**: Type parameters would require parameterizing the singleton instance, which contradicts the singleton pattern. If you need parameterized behavior, use a class instead.

### No Fields or Delegate Views

Objects cannot declare fields in the constructor (since there is no constructor) and cannot use delegate views:

```jo
interface Logger
  def log(msg: String): Unit
end

// Invalid: no constructor parameters
object Service(logger: Logger)  // Error: objects cannot have constructor parameters

// Invalid: no delegate views
object Service
  view Logger = ...  // Error: objects cannot have delegate views
end

// Valid: only intrinsic views (direct implementation)
object Service
  def log(msg: String): Unit = println(msg)
  view Logger
end
```

**Allowed**:

- Method definitions (`def`)
- Intrinsic views (`view I` where the object implements all methods)

**Not allowed**:

- Constructor parameters (fields)
- Delegate views (`view I = expr`)
- Any field declarations (`var` or `val`)

!!! info "Rationale: No Global Variables"
    Jo does not support global variables to avoid problematic global state and complex initialization semantics. Since objects desugar to module-level definitions, allowing fields would introduce global state. Even immutable `val` declarations are prohibited because:

    - **Complex initialization**: Field initialization expressions can be arbitrarily complex
    - **Hidden mutable references**: A `val` field may reference a mutable class instance, creating global mutable state indirectly
    - **Initialization order**: Multiple objects with interdependent fields create initialization order problems

    This restriction:

    - **Prevents global state problems**: Avoids action-at-a-distance bugs and difficult-to-track dependencies
    - **Simplifies initialization**: No initialization order issues between objects
    - **Promotes functional design**: Encourages passing dependencies explicitly

    If you need state or configuration, use a class instance passed as a context parameter.

### No Custom Constructor

Objects cannot define custom constructors:

```jo
object Foo
  // Invalid: objects cannot have constructors
  def Foo() =  // Error: objects cannot define constructors
    // initialization code
  end
end
```

**Rationale**: The initialization of a singleton instance should be trivial to avoid initialization problems.

### Instantiation Prevention

Attempting to instantiate an object type is a compile-time error:

```jo
object Logger
  def log(msg: String): Unit = println(msg)
end

def main =
  val logger = new Logger()  // Error: Cannot instantiate object type Logger
end
```

The singleton instance is accessed through the object name directly:

```jo
def main =
  Logger.log("hello")  // Correct: access via object name
end
```

## View and Adaptation

### View Conformance

Object views are checked the same way as class views:

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

object ConsoleLogger
  def log(msg: String): Unit = println(msg)
  // Missing method: error
  view Logger  // Error: missing required method 'error'
end
```

### Type Adaptation

Objects participate in type adaptation like classes:

```jo
interface Logger
  def log(msg: String): Unit
end

object FileLogger
  def log(msg: String): Unit = ...
  view Logger
end

def useLogger(logger: Logger): Unit = logger.log("test")

def main =
  useLogger(FileLogger)  // OK: FileLogger adapts to Logger via view
end
```

## Examples

### Stateless Object with Interface

Objects are primarily useful for stateless singletons that implement interfaces:

```jo
interface Formatter
  def format(value: Int): String
end

object HexFormatter
  def format(value: Int): String =
    "0x" + intToHexString(value)

  view Formatter
end

object DecFormatter
  def format(value: Int): String =
    value.toString()

  view Formatter
end

def display(value: Int, formatter: Formatter): Unit =
  println(formatter.format(value))

def main =
  display(255, HexFormatter)  // Prints: 0xFF
  display(255, DecFormatter)  // Prints: 255
end
```

### Object in Union Types

Objects can participate in union types, unlike sections:

```jo
object None
class Some[T](value: T)

type Option[T] = None | Some[T]
```
