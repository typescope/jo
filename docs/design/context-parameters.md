# Context Parameters

## Overview

Context parameters provide a mechanism for passing arguments remotely through deep call chains without syntactic overhead. Unlike traditional function parameters that must be passed explicitly at each call site, context parameters are declared at the top level and can be bound remotely, with their bindings automatically propagating through the call stack.

This feature eliminates the need for global variables while retaining their convenience, providing static safety guarantees through an effect system.

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
    printChar ' '
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

### Function Type with Context Dependencies

Function types can declare context parameter dependencies using `receives`:

```jo
type Printer = String => Unit receives indent

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

The default value is evaluated at the point of use when no binding is available.

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

### Effect System and Safety

Context parameters are tracked by an effect system that ensures:

1. **Binding before usage**: A context parameter must be bound before it can be used
2. **Explicit dependencies**: Functions declare their context parameter dependencies via `receives`
3. **Effect checking**: The compiler verifies that all required context parameters are available

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

## Type Annotations

Context parameter dependencies can be annotated in function types:

```jo
type Printer = Doc => String receives pageWidth

// Multiple dependencies
type Renderer = Element => String receives fontSize, pageWidth
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
    printChar(' ')
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
  doc + "\npageWidth = " + intToStr(pageWidth)

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

### Movie Finder Example

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

## Implementation Notes

### Effect Tracking

The compiler maintains an effect system tracking context parameter dependencies:

1. For each function, compute the set of context parameters it directly or indirectly accesses
2. Check at call sites that all required parameters are bound
3. Handle `receives` annotations to verify dependencies match declarations
4. Enforce `allow` restrictions by checking accessed parameters against allowed set

### Closure Representation

Closures capture context parameters by default:

1. At closure creation, snapshot the current bindings of all accessed context parameters
2. Store these bindings in the closure environment
3. When closure is called, use captured bindings instead of call-site bindings

For `receives` parameters in function types:

1. Do NOT capture these parameters in the closure
2. At closure call site, look up these parameters from the current context
3. Fail if parameters are not bound at call site

### Stack Discipline

Context parameter bindings follow stack discipline:

1. `with` clauses push new bindings onto the stack
2. Bindings are popped when the expression completes
3. Lookups search the stack from top to bottom (most recent binding wins)

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

### Why Not Implicit Parameters?

Implicit parameters (Haskell-style) have limitations:

1. **Name conflicts**: Different modules using same name with different types cannot compose
2. **Type-only matching**: Accidental type matches can silently bind wrong parameters
3. **No central documentation**: No single place defining the contract

Context parameters use lexical scoping with top-level declarations to avoid these issues.

### Why Not Global Variables?

Global variables have severe problems:

1. **Mutable globals**: Concurrency issues, hard to reason about, testing requires synchronization
2. **Immutable globals**: Cannot test parametricity (behavior with different values)
3. **Hidden dependencies**: No way to control or restrict which code accesses them

Context parameters provide:

1. **Testability**: Can bind different values in different contexts
2. **Safety**: Effect system ensures binding before use
3. **Control**: `allow` clause provides fine-grained access control
4. **Concurrency**: Stack-based bindings avoid races

### Default Capture in Closures

Closures capture context parameters by default because:

1. **Simplicity**: Matches programmer intuition (like regular variables)
2. **Common case**: Most often, programmers want captured behavior
3. **Explicit override**: `receives` allows opting into call-site binding when needed

## Comparison with Related Features

### vs. Scala Implicit Parameters / Given/Using

**Similarities**:
- Both provide automatic parameter propagation
- Both support default values

**Differences**:
- Context parameters use lexical scoping (top-level declarations) vs. type-based resolution
- Context parameters have identity and prevent accidental type-based matches
- Context parameters support `allow` for fine-grained control
- Context parameters integrate with effect system for static safety

### vs. Reader Monad

**Similarities**:
- Both thread context through computations
- Both support shadowing/local overrides

**Differences**:
- Context parameters are built into the language, no monad wrapper needed
- Context parameters have zero syntactic overhead
- Context parameters support partial application via `allow`
- No need for explicit lift/unlift operations

### vs. Dependency Injection

**Similarities**:
- Both provide inversion of control
- Both enable testing with different implementations

**Differences**:
- Context parameters are language-level, not framework-based
- Context parameters are statically checked
- No need for registration/configuration infrastructure
- Context parameters support fine-grained temporary overrides

## Summary

Context parameters provide a principled mechanism for remote parameter passing that:

- Eliminates boilerplate from threading parameters through call chains
- Provides safety guarantees through an effect system
- Enables effect parametricity and capability-based security
- Maintains testability and modularity without global variables
- Integrates smoothly with first-class functions
