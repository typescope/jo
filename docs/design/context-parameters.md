# Context Parameters

## Overview

Context parameters provide a mechanism for passing arguments remotely through deep call chains without syntactic overhead. Unlike traditional function parameters that must be passed explicitly at each call site, context parameters are declared at the top level and can be bound remotely, with their bindings automatically propagating through the call stack.

This feature eliminates the need for global variables while retaining their convenience, and it provides safety guarantees through static check.

## Motivation

Context parameters address several fundamental programming needs:

1. **Eliminating boilerplate**: Avoid threading parameters through long call chains when intermediate functions don't use them
2. **Replacing global variables safely**: Provide the convenience of global variables without their downsides (testability, concurrency issues, hidden dependencies)
3. **Configuration propagation**: Pass configuration and contextual information deep into execution without polluting function signatures
4. **Effect parametricity**: Enable parametric effects and fine-grained capability control

## Syntax

### Context Parameter Declaration

```
param_decl = "param" ident ":" type ["=" expr]
```

Context parameters are declared at the top level using the `param` keyword:

```jo
param indent: Int
param pageWidth: Int = 80
param connection: Connection
```

### Using Context Parameters

Context parameters can be referenced directly by name in any function within their scope:

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

### Function with Context Dependencies

Function can declare context parameter dependencies using `receives`:

```jo
def pretty(doc: Doc): String receives pageWidth = ...
```

### Controlling Context Parameters

The `allow` clause restricts which context parameters can be accessed:

```jo
search(keyword) with maxResultCount = 200 allow connection

test() allow none  // Disallow all context parameters
```

## Semantics

### Declaration and Scope

1. **Top-level declaration**: Context parameters are declared at the namespace/file level
2. **Lexical scope**: Context parameters are visible within their lexical scope (namespace/file and imports)
3. **Identity**: Each context parameter declaration establishes a unique identity that prevents accidental name conflicts

### Binding and Propagation

1. **Remote binding**: Context parameters can be bound remotely using `with` clauses
2. **Automatic propagation**: Bindings propagate automatically through function calls in the execution path
3. **Shadowing**: Inner bindings shadow outer bindings following stack discipline
4. **Stack-based extent**: Bindings follow a stack discipline - they are only valid during execution of the expression they bind

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

### Optional Context Parameters

Context parameters can have default values:

```jo
param maxResultCount: Int = 100

def search(keyword: String) = ...maxResultCount...

// If no binding provided, uses default value 100
search("laptop")

// Override with custom value
search("laptop") with maxResultCount = 50
```

Semantically, an optional context parameter:

```jo
param name: T = rhs
```

desugars to:

```jo
param name: T
def name$default: T = rhs
```

The default value is automatically supplied in the scope when no binding is available.

**Restriction**: The default value expression cannot depend on other context parameters (to prevent cycles and semantic surprises).

### First-Class Functions and Capture

First-class functions (lambdas/closures) capture context parameters by default at their creation site:

```jo
param a: Int
param b: Int

def makeFun(x: Int) = (n: Int) => n + a * bar(x)

def bar(x: Int): Int = x * b

def main =
  val f = makeFun(3) with a = 5, b = 10
  f(20)  // No context parameters needed - all captured
```

To defer context parameter binding to the call site instead of capture site, use `receives` in the function type:

```jo
param pageWidth: Int = 78

def pretty(doc: String): String receives pageWidth = ...

type Printer = String => String receives pageWidth

def createPrinter(): Printer receives none =
  (doc: String) => pretty(doc)  // Does NOT capture pageWidth

def main =
  val printer = createPrinter()
  printer("hello") with pageWidth = 100  // pageWidth bound at call site
```

### Static Check and Safety

Context parameters are tracked statically that ensures:

1. **Binding before usage**: A context parameter must be bound before it can be used
2. **Explicit dependencies**: Functions declare their context parameter dependencies via `receives`
3. **Static check**: The compiler verifies that all required context parameters are available

Example of a compile-time error:

```jo
param newLine: String

def foo() = print("Hello" + newLine)

def bar() = foo

def main = bar  // Error: Context parameter not provided: newLine
```

Error message includes a trace showing the dependency chain:

```
---------- Error at hello.jo:7:12 ---------------
| def main = bar
|            ^^^
|            Context parameter not provided: newLine

The following is the trace that leads to the problem:
├── def main = bar	[ hello.jo:7:12 ]
│              ^^^
├── def bar() = foo	[ hello.jo:5:13 ]
│               ^^^
└──     "Hello" + newLine	[ hello.jo:3:17 ]
                  ^^^^^^^
```

### The `receives` Clause

The `receives` keyword serves multiple purposes:

1. **For 2nd-class functions (def)**: Declares context parameter dependencies explicitly
2. **For function types**: Specifies parameters to be received from call site rather than captured
3. **Receives none**: Explicitly states no context parameters are needed

```jo
// 2nd-class function declaration
def process(): Unit receives connection, logger = ...

// Function type - parameters NOT captured
type Handler = Request => Response receives connection

// Explicitly no dependencies
def pure(x: Int): Int receives none = x * 2
```

### The `allow` Clause

The `allow` clause provides fine-grained control over which context parameters may be accessed:

```jo
param maxResultCount: Int
param connection: Connection
param args: Array[String]

def search(keyword: String) = ...

def process =
  search(keyword) with maxResultCount = 200 allow connection
```

In this example:
- `search` can use `maxResultCount` (bound via `with`) and `connection` (allowed)
- `search` cannot use `args` - attempting to do so causes a compile error

**Special form**: `allow none` disallows all context parameters from enclosing context:

```jo
def test = factorial(10) allow none  // factorial cannot use any context parameters
```

## Examples

### Basic Usage

```jo
param indent: Int

def padding(unit: Int): Int = indent * unit

def main =
  val a = padding(2) with indent = 2
  println(a)  // Outputs: 4

  val b = padding(3) with indent = 5
  println(b)  // Outputs: 15
```

### Nested Shadowing

```jo
param alpha: Int
param beta: Int

def foo(n: Int): Int = alpha + beta * n

def main =
  val x = foo(10) with alpha = 3, beta = 6
  println(x)  // Outputs: 63

  val y = foo(
    foo(5) with beta = 3
  ) with alpha = 4, beta = 6
  println(y)  // Inner foo: 4 + 3*5 = 19, Outer foo: 4 + 6*19 = 118
```

### Optional Parameters

```jo
param newLine: Bool = false

def printOpt(s: String): Unit =
  print(s)
  if newLine then print("\n")

def main receives IO.stdout =
  printOpt("hello ")      // No newline (uses default false)
  printOpt("world!") with newLine = true  // With newline
```

### Lambda Capture

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

### Deferred Binding with `receives`

```jo
param pageWidth: Int = 78

type Printer = Doc => String receives pageWidth

def createPrinter(): Printer receives none =
  (doc: Doc) => pretty(doc)

def pretty(doc: Doc): String receives pageWidth =
  doc + "\npageWidth = " + pageWidth

def main =
  val printer = createPrinter()
  println(printer("hello") with pageWidth = 100)  // Uses 100, not 78
```

### Configuration Propagation

```jo
param flip: Bool = false
param double: Bool = false

def foo(n: Int): Int = if flip then 0 - n else n

def bar(n: Int): Int = if double then n * 2 else n

def baz(n: Int): Int = foo(n) + bar(n)

def main =
  println(baz(10) with flip = true, double = false)   // -10 + 10 = 0
  println(baz(10) with flip = false, double = true)   // 10 + 20 = 30
```

### Capability Control

```jo
param connection: Connection
param maxResults: Int
param logger: Logger

def search(query: String): List[Result] receives connection, maxResults = ...

def process(query: String) =
  // search can use connection and maxResults, but NOT logger
  search(query) allow connection, maxResults
```

### Dependency Injection

```jo
type Movie = { name: String, director: String, year: Int }
type MovieFinder = { def findAll(): List[Movie] }

param finder: MovieFinder

def moviesDirectedBy(director: String): List[Movie] =
  val allMovies = finder.findAll()
  allMovies.filter(m => m.director == director)

def main =
  val testFinder: MovieFinder = {
    def findAll(): List[Movie] =
      val movie1 = { name: "A", director: "Hitchcock", year: 1960 }
      val movie2 = { name: "B", director: "Kubrick", year: 1968 }
      List.empty + movie1 + movie2
  }

  val hitchcockMovies = moviesDirectedBy("Hitchcock") with finder = testFinder
  hitchcockMovies.foreach(m => println(m.name))
```

## Design Decisions

### Why Top-Level Declaration?

Top-level declaration provides:

1. **Identity**: Each parameter has a unique identity preventing accidental conflicts
2. **Documentation**: Central place to document contracts and invariants
3. **Discoverability**: IDE can jump from use to declaration
4. **Scoping**: Lexical scoping prevents ambiguity

### Why Not Dynamic Scoping?

Traditional dynamic scoping (like special variables in Common Lisp) has problems:

1. **No identity**: Names are like tags, leading to accidental conflicts
2. **No central documentation**: Contract scattered across uses
3. **Composability issues**: Cannot safely compose modules with same parameter names

Context parameters solve these with lexical scoping while keeping remote binding.

### Why Not Scala Implicit Parameters

1. Context parameters use name-based resolution vs. type-based resolution
2. Context parameters have identity and prevent accidental type-based matches
3. Context parameters support `allow` for fine-grained control
4. Context parameters propagate automatically in the call chain

### Why Not Global Variables?

Global variables have severe problems:

1. **Mutable globals**: Concurrency issues, hard to reason about, testing requires synchronization
2. **Immutable globals**: Cannot test parametricity (behavior with different values)
3. **Hidden dependencies**: No way to control or restrict which code accesses them

Context parameters provide:

1. **Testability**: Can bind different values in different contexts
2. **Safety**: Static check ensures binding before use
3. **Control**: `allow` clause provides fine-grained access control

### Default Capture in Closures

Closures capture context parameters by default because:

1. **Simplicity**: Matches programmer intuition (like regular variables)
2. **Common case**: Most often, programmers want captured behavior
3. **Explicit override**: `receives` allows opting into call-site binding when needed

## Summary

Context parameters provide a principled mechanism for remote parameter passing that:

- Eliminates boilerplate from threading parameters through call chains
- Provides safety guarantees through static check
- Enables effect parametricity and capability-based security
- Maintains testability and modularity without global variables
- Integrates smoothly with first-class functions
