# Deferred Functions

Deferred functions let a module declare function interfaces that are filled in at compile
time. This enables framework-based programming, dependency injection, and flexible entry
points without any runtime overhead.

For the formal specification, see
[Deferred Functions](../language/definitions/deferred-functions.md).

## Framework Development

A framework can define abstract operations that callers implement, then control the
overall execution flow:

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

```bash
bin/jo compile --python \
  --link jo.main=Framework.runApp \
  --link Framework.init=MyApp.init \
  --link Framework.process=MyApp.process \
  --link Framework.cleanup=MyApp.cleanup \
  framework.jo implementation.jo -o app.py
```

## Dependency Injection

Abstract away dependencies so the same module can be compiled against different
implementations — for example, production vs. test:

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

```bash
# Production
bin/jo compile --python service.jo \
  --link Service.getDatabase=Production.PostgresDB \
  --link Service.getLogger=Production.FileLogger \
  -o service-prod.py

# Testing
bin/jo compile --python service.jo \
  --link Service.getDatabase=Testing.MockDB \
  --link Service.getLogger=Testing.MemoryLogger \
  -o service-test.py
```

## Custom Entry Points

`--link jo.main=<func>` makes any function the program entry point:

```jo
namespace MyApp

def startup: Unit =
  println "Custom entry point"
```

```bash
bin/jo compile --python myapp.jo --link jo.main=MyApp.startup -o myapp.py
```

## Separate Compilation

Deferred functions work with precompiled libraries:

```bash
# Build the framework as a library
bin/jo compile --sast lib/ framework.jo

# Build the application, linking against the precompiled library
bin/jo compile --python app.jo --lib lib/ \
  --link Framework.func=App.impl \
  -o app.py
```

## Example: Calculator Framework

```jo
// options: --link Calculator.add=Math.add --link Calculator.multiply=Math.multiply

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
