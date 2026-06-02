# Deferred Functions

Most dependency injection frameworks operate at runtime: they wire up objects through
reflection, XML config, or annotation scanning, and report missing or mistyped
dependencies as runtime crashes.

Jo's deferred functions move this to compile time. A deferred function declares an
interface that must be filled in before the program can be built. The linker validates
types, the compiler inlines the bindings, and there is zero runtime overhead.


## Inverting Package Dependencies

The standard problem: `reportLib` wants to render documents, but shouldn't import a
specific renderer — that would couple a general-purpose library to a concrete backend.

```jo
// reportLib/report.jo — no imports, depth-0 library
namespace Report

defer def newRenderer(): Renderer

def generate(title: String, body: String): String =
  newRenderer().render("# " + title + "\n\n" + body)
```

The concrete renderer is supplied at compile time:

```bash
# Production build — HTML renderer
bin/jo compile --python app.jo \
  --link Report.newRenderer=HtmlLib.newHtmlRenderer \
  -o app.py

# Test build — in-memory stub
bin/jo compile --python app.jo \
  --link Report.newRenderer=TestLib.newStubRenderer \
  -o app-test.py
```

`report` carries no imports, so it remains a depth-0 library. The dependency on a
concrete renderer is a concern of the application, not the library. If the linked
function's signature doesn't match the deferred declaration, the compiler stops with
a type error — no runtime surprises.

## Framework-Controlled Entry Points

Frameworks that own the application lifecycle while staying decoupled from application
code:

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

## Swapping Implementations

The same module compiled against different implementations — production vs. test — with
no source changes:

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

## See Also

- [Deferred Functions](../language/definitions/deferred-functions.md) — formal specification
