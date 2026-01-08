# Object Definitions

## Overview

Object definitions provide a convenient syntax for defining singleton values with associated behavior. An object definition combines a value binding with a class definition, ensuring that exactly one instance of the class exists and is accessible through a named identifier.

## Motivation

Many programs need singleton values—unique instances that represent configuration, shared state, or stateless utilities. Traditional approaches require manually defining both a class and a singleton instance:

```jo
class Logger
  def log(msg: String): Unit = println(msg)
end

def Logger: Logger = new Logger()
```

This pattern is verbose and error-prone: nothing prevents accidentally creating additional instances with `new Logger()`.

Object definitions provide a concise syntax that:

- **Eliminates boilerplate**: Combines class and singleton definition in one construct
- **Prevents instantiation**: The compiler rejects attempts to instantiate object types
- **Clarifies intent**: Makes singleton semantics explicit in the definition
- **Backend flexibility**: Allows different compilation strategies (lazy/eager initialization)

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

### Object with Methods and State

Objects can have mutable state and methods:

```jo
object Counter
  var count: Int = 0

  def increment(): Unit = count = count + 1
  def get(): Int = count
  def reset(): Unit = count = 0
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

class A
  // members...
end
```

The `def A: A` binding provides access to the singleton instance, while the class definition specifies its structure and behavior.

**Important**: The exact initialization strategy (lazy vs. eager) is determined by the backend. Different backends may choose different strategies based on their runtime characteristics.

### Top-Level Restriction

Object definitions must appear at the top level of a namespace, just like class and interface definitions:

```jo
// Valid: top-level object
object Config
  val timeout: Int = 30
end

def process(): Unit =
  // Invalid: objects cannot be local
  object LocalConfig  // Error: object definitions must be top-level
    val retry: Int = 3
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
  var value: T = ...
end

// Valid: concrete type
object IntContainer
  var value: Int = 0
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
end

// Invalid: no delegate views
object Service
  view Logger = someLogger  // Error: objects cannot have delegate views
end

// Valid: only intrinsic views (direct implementation)
object Service
  def log(msg: String): Unit = println(msg)
  view Logger
end
```

**Allowed**:
- Intrinsic views (`view I` where the object implements all methods)
- Local variable declarations using `var` and `val`
- Method definitions

**Not allowed**:
- Constructor parameters (fields)
- Delegate views (`view I = expr`)

**Rationale**: Objects have no construction phase, so constructor parameters and delegate views (which initialize delegation fields) don't make sense. All state must be initialized inline.

### No Custom Constructor

Objects cannot define custom constructors:

```jo
object Config
  val timeout: Int

  // Invalid: objects cannot have constructors
  def this(timeout: Int) =  // Error: objects cannot define constructors
    this.timeout = timeout
end
```

**Rationale**: The singleton instance is created by the backend without explicit constructor calls. All initialization must use default expressions.

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

## Type Checking

### Object Definition Validation

When checking an object definition, verify:

1. **Top-level position**: Object must be defined at namespace level, not nested
2. **No type parameters**: Object identifier must not be followed by type parameter list
3. **No constructor parameters**: Object definition must not include parameter list after identifier
4. **No custom constructor**: Object body must not contain constructor definitions
5. **View restrictions**: Only intrinsic views allowed (direct implementation), no delegate views
6. **Member validity**: All members follow standard class member rules

### Instantiation Check

When encountering `new T()`, verify:

1. **Class type check**: `T` must resolve to a class type, not an object type
2. If `T` is an object type, report error: "Cannot instantiate object type T"

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

### Configuration Object

```jo
object AppConfig
  val serverPort: Int = 8080
  val maxConnections: Int = 100
  val timeout: Int = 30

  def getConnectionString(): String =
    "localhost:" + serverPort.toString()
end

def main =
  println("Server running on port " + AppConfig.serverPort.toString())
  println("Connection: " + AppConfig.getConnectionString())
end
```

### Singleton with State

```jo
object RequestCounter
  var totalRequests: Int = 0
  var failedRequests: Int = 0

  def recordRequest(): Unit = totalRequests = totalRequests + 1
  def recordFailure(): Unit = failedRequests = failedRequests + 1

  def getSuccessRate(): Float =
    if totalRequests == 0 then 1.0
    else intToFloat(totalRequests - failedRequests) / intToFloat(totalRequests)

  def reset(): Unit =
    totalRequests = 0
    failedRequests = 0
end

def main =
  RequestCounter.recordRequest()
  RequestCounter.recordRequest()
  RequestCounter.recordFailure()

  println("Success rate: " + RequestCounter.getSuccessRate().toString())
  // Prints: Success rate: 0.666667
end
```

### Object with Interface View

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
  def debug(msg: String): Unit
end

object ConsoleLogger
  def log(msg: String): Unit = println("[INFO] " + msg)
  def error(msg: String): Unit = eprintln("[ERROR] " + msg)
  def debug(msg: String): Unit = println("[DEBUG] " + msg)

  view Logger
end

def processWithLogging(logger: Logger): Unit =
  logger.log("Starting process")
  logger.debug("Processing data")
  logger.log("Process completed")

def main =
  processWithLogging(ConsoleLogger)
end
```

### Multiple Objects with Shared Interface

```jo
interface Storage
  def save(key: String, value: String): Unit
  def load(key: String): Option[String]
end

object MemoryStorage
  var store: Map[String, String] = emptyMap()

  def save(key: String, value: String): Unit =
    store = store.insert(key, value)

  def load(key: String): Option[String] =
    store.lookup(key)

  view Storage
end

object FileStorage
  def save(key: String, value: String): Unit =
    writeFile("/tmp/" + key, value)

  def load(key: String): Option[String] =
    if fileExists("/tmp/" + key) then
      Some(readFile("/tmp/" + key))
    else
      None

  view Storage
end

def useStorage(storage: Storage): Unit =
  storage.save("user", "Alice")
  val loaded = storage.load("user")
  loaded match
    case Some(name) => println("Loaded: " + name)
    case None => println("Not found")
  end

def main =
  useStorage(MemoryStorage)  // Uses in-memory storage
  useStorage(FileStorage)    // Uses file-based storage
end
```

### Utility Object (Stateless)

```jo
object MathUtils
  def abs(x: Int): Int = if x < 0 then -x else x

  def max(x: Int, y: Int): Int = if x > y then x else y

  def min(x: Int, y: Int): Int = if x < y then x else y

  def clamp(value: Int, lower: Int, upper: Int): Int =
    min(max(value, lower), upper)
end

def main =
  val x = MathUtils.abs(-42)     // 42
  val y = MathUtils.max(10, 20)  // 20
  val z = MathUtils.clamp(15, 0, 10)  // 10

  println(x.toString() + ", " + y.toString() + ", " + z.toString())
end
```

### Object with Multiple Views

```jo
interface Cache
  def get(key: String): Option[String]
  def put(key: String, value: String): Unit
end

interface Metrics
  def getHitCount(): Int
  def getMissCount(): Int
  def getHitRate(): Float
end

object GlobalCache
  var store: Map[String, String] = emptyMap()
  var hits: Int = 0
  var misses: Int = 0

  def get(key: String): Option[String] =
    store.lookup(key) match
      case Some(v) =>
        hits = hits + 1
        Some(v)
      case None =>
        misses = misses + 1
        None

  def put(key: String, value: String): Unit =
    store = store.insert(key, value)

  def getHitCount(): Int = hits
  def getMissCount(): Int = misses
  def getHitRate(): Float =
    val total = hits + misses
    if total == 0 then 0.0 else intToFloat(hits) / intToFloat(total)

  view Cache
  view Metrics
end

def main =
  // Use as Cache
  GlobalCache.put("user", "Alice")
  val user = GlobalCache.get("user")

  // Use as Metrics
  println("Hit rate: " + GlobalCache.getHitRate().toString())

  // Pass to functions expecting different interfaces
  def reportMetrics(m: Metrics): Unit =
    println("Hits: " + m.getHitCount().toString())

  def clearCache(c: Cache): Unit =
    // Cache operations...

  reportMetrics(GlobalCache)  // Adapts via Metrics view
end
```

## Design Decisions

### Why Prevent Instantiation?

Object types represent singleton values by definition. Allowing instantiation would:

- **Violate singleton semantics**: Multiple instances contradict the purpose
- **Cause confusion**: `new Logger()` vs `Logger` would be different instances
- **Break expectations**: Code expecting singleton behavior could receive different instances

By preventing instantiation, the language enforces singleton semantics at compile time.

### Why No Type Parameters?

Type parameters would require the singleton instance to be parameterized:

```jo
object Container[T]  // What is T for the singleton instance?
  var value: T = ...
end
```

This creates a fundamental conflict:
- Singletons are unique instances
- Type parameters require multiple instantiations for different types

If you need parameterized behavior, use a class with a companion object pattern:

```jo
class Container[T]
  var value: T = ...
end

object Container
  def empty[T](default: T): Container[T] = new Container[T](default)
end
```

### Why Restrict to Intrinsic Views Only?

Delegate views require initialization expressions that reference constructor parameters:

```jo
class Service(logger: Logger)
  view Logger = logger  // Delegates to constructor parameter
end
```

Objects have no construction phase or parameters, making delegate views impossible. Only intrinsic views (direct implementation) make sense for objects:

```jo
object Service
  def log(msg: String): Unit = println(msg)
  view Logger  // Intrinsic view: object implements all methods
end
```

This restriction keeps the object model simple and consistent.

### Why Backend-Determined Initialization?

Different compilation backends have different runtime characteristics:

- **JavaScript backend**: May use lazy initialization (initialize on first access)
- **Native backend**: May use eager initialization (initialize at program start)
- **JVM backend**: May use static initialization blocks

Allowing backends to choose the initialization strategy enables optimal performance for each target platform while maintaining consistent semantics: regardless of when initialization occurs, the object behaves as a singleton.

### Why Top-Level Only?

Allowing nested objects would create ambiguity:

```jo
def process(): Unit =
  object LocalConfig
    val timeout: Int = 30
  end

  LocalConfig.timeout  // New instance each call? Shared across calls?
end
```

Top-level restriction ensures:
- Clear singleton semantics (not recreated)
- Simple mental model
- Consistent compilation strategy

If you need local singleton-like behavior, use a local class with a single instance:

```jo
def process(): Unit =
  class LocalConfig
    val timeout: Int = 30
  end

  val config = new LocalConfig()
  config.timeout
end
```

## Summary

Object definitions provide concise syntax for singleton values with associated behavior. An object definition desugars to a value binding and a class definition, with compile-time prevention of additional instantiation. Objects must be top-level, cannot have type parameters or constructor parameters, and support only intrinsic views. This design provides a simple, safe singleton mechanism with backend-flexible initialization strategies.
