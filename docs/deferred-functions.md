# Deferred Functions and Flexible Linking

## Overview

Deferred functions are a powerful language feature in Jo that enable framework-based programming, dependency injection, and flexible application architecture. A deferred function declares an interface without providing an implementation, creating an extension point that can be bound at compile time using the `-link` option.

## Basic Syntax

### Declaring Deferred Functions

```jo
defer def functionName(param1: Type1, param2: Type2): ReturnType
```

The `defer` keyword indicates that this function has no implementation in the current module. It must be linked to a concrete implementation at compile time.

### Linking Deferred Functions

Use the `-link` compiler option to bind deferred functions to their implementations:

```bash
bin/jo build myapp.jo -link Source.deferredFunc=Target.concreteFunc -o myapp
```

The syntax is: `-link <deferred-function-path>=<implementation-path>`

## Key Concepts

### 1. Extension Points

Deferred functions create explicit extension points in your code where behavior can be customized:

```jo
namespace Framework

section Database
  defer def connect(host: String): Unit
  defer def query(sql: String): String
  defer def disconnect(): Unit
end
```

### 2. Type Safety

The compiler validates that linked implementations conform to the deferred function's type signature. If types don't match, a compile-time error is reported.

### 3. Compile-Time Binding

Unlike runtime dependency injection, linking happens at compile time:
- Zero runtime overhead
- No reflection required
- Type errors caught early
- Enables whole-program optimization

### 4. Default Implementations

Deferred functions can optionally provide default implementations. If no `-link` option is provided and no default exists, the compiler reports an error.

## Use Cases

### Framework Development

Frameworks can define abstract operations that users implement:

```jo
// framework.jo
namespace Framework

defer def init(): Unit
defer def process(x: Int): Int
defer def cleanup(): Unit

def runApp: Unit =
  init()
  val result = process(42)
  result p
  cleanup()
```

```jo
// implementation.jo
namespace MyApp

def init(): Unit = println "Starting..."
def process(x: Int): Int = x * 2
def cleanup(): Unit = println "Done"
```

Compile with:
```bash
bin/jo build -no-detect-main \
  -link jo.Main.main=Framework.runApp \
  -link Framework.init=MyApp.init \
  -link Framework.process=MyApp.process \
  -link Framework.cleanup=MyApp.cleanup \
  framework.jo implementation.jo -o app
```

### Dependency Injection

Abstract away dependencies for testing or modularity:

```jo
// service.jo
namespace Service

defer def getDatabase(): Database
defer def getLogger(): Logger

def processRequest(request: Request): Response =
  val db = getDatabase()
  val logger = getLogger()
  logger.log("Processing request")
  db.query(request.toSQL())
```

Link to different implementations for production vs. testing:

```bash
# Production
bin/jo build service.jo \
  -link Service.getDatabase=Production.PostgresDB \
  -link Service.getLogger=Production.FileLogger \
  -o service-prod

# Testing
bin/jo build service.jo \
  -link Service.getDatabase=Testing.MockDB \
  -link Service.getLogger=Testing.MemoryLogger \
  -o service-test
```

### Custom Entry Points

The `-no-detect-main` flag combined with `-link jo.Main.main=...` allows any function to become the entry point:

```jo
namespace MyApp

// Not the traditional 'main' function
def startup: Unit =
  println "Custom entry point"
  // application logic
```

Compile with:
```bash
bin/jo build myapp.jo -no-detect-main -link jo.Main.main=MyApp.startup -o myapp
```

This is particularly useful for:
- Framework-controlled applications
- Testing different entry scenarios
- Embedding in larger systems

## Compiler Options

### `-link <source>=<target>`

Binds a deferred function to an implementation.
- Can be specified multiple times for different bindings
- Type conformance is checked at compile time
- User mappings take precedence over compiler defaults

### `-no-detect-main`

Disables automatic main function detection.
- Must explicitly link `jo.Main.main` to an entry point
- Useful when the framework controls the entry point
- Enables testing alternative entry scenarios

## Examples

### Example 1: Simple Calculator Framework

```jo
// options: -link Calculator.add=Math.add -link Calculator.multiply=Math.multiply

namespace Test

section Calculator
  defer def add(a: Int, b: Int): Int
  defer def multiply(a: Int, b: Int): Int

  def compute(x: Int, y: Int): Int =
    val sum = add(x, y)
    multiply(sum, 2)
end

section Math
  def add(a: Int, b: Int): Int = a + b
  def multiply(a: Int, b: Int): Int = a * b
end

def main =
  Calculator.compute(3, 7) p  // Output: 20
```

### Example 2: Framework-Controlled Entry Point

```jo
// options: -no-detect-main -link jo.Main.main=Framework.runApp
//          -link Framework.init=Implementation.init
//          -link Framework.process=Implementation.process

namespace Test

section Framework
  defer def init(): Unit
  defer def process(x: Int): Int

  def runApp: Unit =
    init()
    val result = process(42)
    result p
end

section Implementation
  def init(): Unit = println "Initialized"
  def process(x: Int): Int = x * 2
end
```

## Advanced Features

### Separate Compilation

Deferred functions work seamlessly with separate compilation:

1. Build framework as a library:
   ```bash
   bin/jo build-lib framework.jo -d lib/
   ```

2. Build application linking to framework:
   ```bash
   bin/jo build app.jo -lib lib/ \
     -link Framework.func=App.impl \
     -o app
   ```

### Context Parameters

Deferred functions can use context parameters (receives clauses):

```jo
import jo.IO.stdout

defer def log(msg: String): Unit receives stdout

def process(): Unit =
  log("Processing started")
  // ...
  log("Processing complete")
```

### Multiple Namespaces

Link across different namespaces and modules:

```bash
bin/jo build \
  -link Framework.Core.init=Plugins.SQLite.initialize \
  -link Framework.Core.query=Plugins.SQLite.executeQuery \
  framework.jo plugins.jo -o app
```

## Error Handling

### Missing Implementation

If a deferred function is not linked and has no default implementation:

```
[Error] Deferred function Test.Service.doWork has no default implementation and is not linked
```

### Type Mismatch

If the linked implementation doesn't match the expected type:

```
[Error] Type mismatch: Test.Math.add expects (Int, Int): Int but Test.Impl.add has type (String, String): String
```

### Conflicting Mappings

If user-supplied link mapping conflicts with compiler defaults:

```
[Warning] User-supplied link mapping ignored due to conflicts with compiler default:
  jo.Predef.abort=Custom.abort (was jo.Predef.abort=jo.runtime.native.Core.abortImpl)
```

## Design Rationale

### Why Deferred Functions?

1. **Explicit Dependencies**: Deferred functions make dependencies visible and explicit in the type system
2. **Compile-Time Safety**: Type checking ensures all bindings are correct before runtime
3. **Zero Overhead**: No runtime lookup or reflection needed
4. **Framework Flexibility**: Frameworks can define contracts without implementations
5. **Capability-Based Security**: Deferred functions act as capability declarations that platform controls
6. **Testing Support**: Easy to swap implementations for testing
7. **Whole-Program Optimization**: Compiler can inline and optimize across bindings
8. **Auditable Security**: All capability boundaries are visible in source code

### Comparison with Alternatives

| Feature | Deferred Functions | OOP Interfaces | Runtime DI |
|---------|-------------------|----------------|------------|
| Type Safety | Compile-time | Compile-time | Runtime |
| Performance | Zero overhead | Virtual dispatch | Reflection overhead |
| Flexibility | Link-time | Inheritance-based | Configuration-based |
| Testability | High | Medium | High |
| Complexity | Low | Medium | High |

## Best Practices

1. **Use descriptive names**: Make deferred function purposes clear
2. **Document contracts**: Specify pre/post-conditions and invariants
3. **Provide defaults when sensible**: Reduce configuration burden
4. **Group related deferred functions**: Use sections to organize related extension points
5. **Validate early**: Let the compiler catch binding errors at compile time
6. **Separate concerns**: Use deferred functions to isolate platform-specific or environment-specific code

## Conclusion

Deferred functions with flexible linking provide a powerful, type-safe mechanism for building modular, testable, and framework-oriented applications. By moving dependency resolution to compile time, Jo achieves the flexibility of dependency injection with zero runtime overhead and complete type safety.
