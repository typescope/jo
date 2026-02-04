# Context Parameters

## Overview

Context parameters provide a mechanism for passing arguments remotely through deep call chains without syntactic overhead.

## Motivation

Context parameters address several fundamental programming needs:

1. **Replacing global variables safely**: Provide the convenience of global variables without their downsides (testability, concurrency issues, hidden dependencies)
2. **Configuration propagation**: Pass configuration and contextual information deep into execution without polluting function signatures
3. **Dependency injection**: Enable safe and lightweight dependency injection without frameworks
4. **Static capability control**: Enable fine-grained capability control to enforce security policies

## Quick Tour

### Declaring and Using Context Parameters

Context parameters are declared at the top level using the `param` keyword, and can be referenced directly by name in any function within their scope:

```jo
param indent: Int

def line(text: String): Unit =
  var i = 0
  while i < indent do
    print ' '
    i = i + 1
  println text
```

### Binding Context Parameters

Context parameters are bound using the `with` clause:

```jo
line("hello") with indent = 5
```

Multiple parameters can be bound simultaneously:

```jo
foo(10) with alpha = 3, beta = 6
```

### Default Values

Context parameters can have default values, making them optional:

```jo
param maxResultCount: Int = 100

def search(keyword: String) = ...maxResultCount...

// If no binding provided, uses default value 100
search("laptop")

// Override with custom value
search("laptop") with maxResultCount = 50
```

### Automatic Propagation and Shadowing

Bindings propagate automatically through function calls. Inner bindings shadow outer bindings during their execution:

```jo
param fontSize: Int

def renderText(text: String) = ...fontSize...

def renderH2(h2: Element) =
  renderText(h2.text) with fontSize = 20  // Shadows outer binding

def renderDiv(div: Element) =
  ...renderText(p.text)     // Uses fontSize from outer context
  ...renderH2(h2)          // Temporarily uses fontSize = 20
  ...renderText(label.text) // Back to outer fontSize

renderDiv(div) with fontSize = 14
```

### Lambda Capture

Closures capture context parameters by default at their creation site, just like regular variables:

```jo
param indent: Int

def line(text: String): Unit =
  var i = 0
  while i < indent do
    print ' '
    i = i + 1
  println(text)

def makeLinePrinter(): String => Unit =
  (text) => line(text)

def main =
  val f = makeLinePrinter with indent = 5  // indent captured in closure
  f("hello")  // Uses captured indent = 5
```

To defer context parameter binding to the call site instead of capture site, use `receives` in the lambda type:

```jo
param pageWidth: Int = 78

type Printer = Doc => String receives pageWidth

def createPrinter(): Printer receives none =
  (doc: Doc) => pretty(doc)  // Does NOT capture pageWidth

def main =
  val printer = createPrinter()
  printer("hello") with pageWidth = 100  // pageWidth bound at call site
```

### Static Safety

Context parameters are tracked statically. The compiler verifies that all required context parameters are bound before use:

```jo
param connection: Connection

def query(sql: String): List[Row] = connection.execute(sql)

def getUsers(): List[Row] = query("SELECT * FROM users")

def main = getUsers()  // Error: Context parameter not provided: connection
```

The error message includes a trace showing the full dependency chain, making it easy to understand why a context parameter is needed:

```
---------- Error at app.jo:7:12 ---------------
| def main = getUsers()
|            ^^^^^^^^^^
|            Context parameter not provided: connection

The following is the trace that leads to the problem:
├── def main = getUsers()	[ app.jo:7:12 ]
│              ^^^^^^^^^^
├── def getUsers() = query("SELECT * FROM users")	[ app.jo:5:22 ]
│                    ^^^^^
└──     connection.execute(sql)	[ app.jo:3:29 ]
        ^^^^^^^^^^
```

### Capability Control with `allow`

The `allow` clause restricts which context parameters can be accessed:

```jo
allow connection in
  search(keyword) with maxResultCount = 200

allow none in test()  // Disallow all context parameters
```

When a disallowed context parameter is used, the compiler reports the violation with a trace:

```jo
param connection: Connection
param logger: Logger

def query(sql: String) = ...connection...logger...

def process(sql: String) =
  allow connection in query(sql)  // Error: logger is not allowed
```

```
---------- Error at app.jo:7:3 ---------------
|   allow connection in query(sql)
|                       ^^^^^^^^^^
|   Parameter not allowed: logger

The following is the trace that leads to the problem:
├──   allow connection in query(sql)	[ app.jo:7:3 ]
│                         ^^^^^^^^^^
└── def query(sql: String) = ...connection...logger...	[ app.jo:4:42 ]
                                              ^^^^^^
```

## Use Cases

### Configuration Propagation

```jo
param pageWidth: Int = 80
param indentSize: Int = 2

def indent(level: Int): String =
  var padding = ""
  for _ in 0 until level * indentSize do padding = padding + " "
  padding

def formatLine(level: Int, text: String): String =
  val line = indent(level) + text
  if line.length > pageWidth then line.take(pageWidth - 3) + "..."
  else line

def formatBlock(level: Int, lines: List[String]): String =
  lines.map(line => formatLine(level, line)).join("\n")

def main =
  val lines = ["hello", "world"]
  // Compact format for narrow terminals
  val compact = formatBlock(1, lines) with pageWidth = 40, indentSize = 4
  // Wide format with default indent
  val wide = formatBlock(1, lines) with pageWidth = 120
```

### Dependency Injection

```jo
class Movie(name: String, director: String, year: Int)

interface MovieFinder
  def findAll(): List[Movie]
end

param finder: MovieFinder

def moviesDirectedBy(director: String): List[Movie] =
  val allMovies = finder.findAll()
  allMovies.filter(m => m.director == director)

class TestFinder
  def findAll(): List[Movie] =
    val movie1 = Movie("A", "Hitchcock", 1960)
    val movie2 = Movie("B", "Kubrick", 1968)
    [movie1, movie2]

  view MovieFinder
end

def main =
  val testFinder = new TestFinder

  val hitchcockMovies = moviesDirectedBy("Hitchcock") with finder = testFinder
  for m in hitchcockMovies do println m.name
```

### Safe Circular Dependencies

Most popular Java frameworks support circular dependencies, even though it is widely recognized as problematic due to obscure semantics and subtle initialization issues. With context parameters, circular dependencies can be made safe:

```jo
interface ServiceFoo
  def foo(): Unit receives barService
end

interface ServiceBar
  def bar(): Unit receives fooService
end

param fooService: ServiceFoo
param barService: ServiceBar

class FooImpl
  def foo(): Unit =
    println("foo calling bar")
    barService.bar()

  view ServiceFoo
end

class BarImpl
  def bar(): Unit =
    println("bar calling foo")
    fooService.foo()

  view ServiceBar
end

def main =
  val fooImpl = new FooImpl
  val barImpl = new BarImpl
  fooImpl.foo() with fooService = fooImpl, barService = barImpl
```

Here, the service `fooService` depends on `barService` and vice versa. Context parameters enable both static control of dependencies and safe initialization: the static type system ensures that no context parameters may be used without being bound. Unlike framework-based circular injection which may fail at runtime with partially initialized objects, context parameters guarantee that both services are fully constructed before either can be used.

## Design Rationale

### Why Not Dynamic Scoping?

Traditional dynamic scoping (like special variables in Lisp and scoped values in Java) has problems:

1. **No identity**: Special variables are like tags, leading to accidental conflicts
2. **No static safety**: Missing bindings are only detected at runtime, not at compile time
3. **No deep capture in lambdas**: Closures cannot reliably capture dynamically scoped values for later use
4. **No abuse prevention**: No mechanism like `allow` to restrict which code may access which bindings

### Why Not Scala Implicit Parameters

1. Context parameters use name-based resolution vs. type-based resolution
2. Context parameters have identity and prevent accidental type-based matches
3. Context parameters support `allow` for fine-grained control
4. Context parameters propagate automatically in the call chain

### Default Capture in Closures

Closures capture context parameters by default because:

1. **Simplicity**: Matches programmer intuition (like regular variables)
2. **Common case**: Most often, programmers want captured behavior
3. **Explicit override**: `receives` allows opting into call-site binding when needed
