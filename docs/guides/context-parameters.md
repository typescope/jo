# Context Parameters

Imagine building a pretty-printer. Every function that formats output needs to know the
current `pageWidth` and `indentSize`. You could add them as arguments to every function —
but then every caller needs them too, and every caller's caller, all the way up the stack.
Or you could make them global — but then tests interfere with each other and concurrent
calls corrupt each other's settings.

Context parameters offer a third path: declare the value once, bind it at the call site
that has the relevant context, and let it propagate automatically through the call chain.
No argument threading. No globals. Full static safety.

For the formal specification, see
[Context Parameters](../language/definitions/context-parameters.md).

## Declaring and Binding

Declare a context parameter with `param` at the top level:

```jo
param pageWidth: Int

def truncate(s: String): String =
  if s.size > pageWidth then s.take(pageWidth - 3) + "..."
  else s
```

Bind it at the call site with `with ... in`:

```jo
with pageWidth = 80 in truncate("A very long line...")
```

The binding propagates to every function called within the expression — `truncate` and
anything `truncate` calls — without any extra plumbing.

## Default Values

A context parameter with a default is optional. Callers that don't care about the value
get the default; those that do can override it:

```jo
param pageWidth: Int = 80
param indentSize: Int = 2

formatBlock(lines)                                          // uses defaults: 80, 2
with pageWidth = 40 in formatBlock(lines)                  // narrow terminal: 40, 2
with pageWidth = 40, indentSize = 4 in formatBlock(lines)  // fully customized
```

## Propagation and Shadowing

Bindings travel through the entire call chain. A `with` on an outer call covers all
inner calls unless an inner call rebinds the same parameter:

```jo
param fontSize: Int

def renderText(text: String) = ...fontSize...

def renderHeading(h: Element) =
  with fontSize = 24 in renderText(h.text)  // larger font just for headings

def renderPage(page: Page) =
  renderText(page.body)    // fontSize from outer binding
  renderHeading(page.h1)   // temporarily fontSize = 24, then back
  renderText(page.footer)  // fontSize from outer binding again

with fontSize = 14 in renderPage(page)
```

The inner binding for `fontSize = 24` is scoped to `renderHeading` and its callees only.
Execution of `renderPage` continues with `fontSize = 14` before and after.

## Lambda Capture vs. Deferred Binding

Lambdas capture context parameters at creation time, just like ordinary variables:

```jo
param indent: Int

def makeFormatter(): String => String =
  (text) => " ".repeat(indent) + text

val fmt = with indent = 4 in makeFormatter()  // indent = 4 captured
fmt("hello")                                   // "    hello" — no `with` needed
```

Sometimes you want the opposite: a lambda that picks up its context parameters from
wherever it is *called*, not from where it was *created*. Mark the lambda type with
`receives`:

```jo
param pageWidth: Int = 78

type Printer = Doc => String receives pageWidth

def makePrinter(): Printer receives none =
  (doc: Doc) => pretty(doc)   // pageWidth NOT captured here

val printer = makePrinter()
with pageWidth = 80 in printer(doc1)   // uses 80
with pageWidth = 120 in printer(doc2)  // uses 120
```

This is useful for creating reusable function values whose behavior adapts to the
context at each call site.

## Bounding Capabilities with `allow`

`allow` is a security-oriented feature: it lets you explicitly state which context
parameters a block of code is permitted to access. Any parameter used outside the
declared set is a compile-time error, even transitively through function calls.

```jo
param connection: Connection
param logger: Logger
param maxResults: Int

def search(query: String) = ...connection...maxResults...

def process(query: String) =
  // search may use connection and maxResults, but NOT logger
  allow connection, maxResults in
    with maxResults = 200 in search(query)
```

If `search` (or anything it calls) tried to access `logger`, the compiler would report
the violation with a trace showing exactly which call led to the forbidden access.

`allow none` is the extreme: the block cannot access any context parameter from the
enclosing scope:

```jo
allow none in factorial(10)   // factorial is pure — no context parameters at all
```

## Use Cases

### Configuration Propagation

Context parameters thread rendering configuration through a formatter without polluting
every function signature:

```jo
param pageWidth: Int = 80
param indentSize: Int = 2

def indent(level: Int): String = " ".repeat(level * indentSize)

def formatLine(level: Int, text: String): String =
  val line = indent(level) + text
  if line.size > pageWidth then line.take(pageWidth - 3) + "..."
  else line

// Compact mode for narrow terminals:
with pageWidth = 40, indentSize = 4 in formatBlock(ast)
```

### Dependency Injection

Context parameters provide clean dependency injection: callers supply the
implementation, functions state only what they need:

```jo
interface MovieFinder
  def findAll(): List[Movie]
end

param finder: MovieFinder

def moviesDirectedBy(director: String): List[Movie] =
  finder.findAll().filter(m => m.director == director)

// In tests, supply a test double:
with finder = new TestFinder in moviesDirectedBy("Kubrick")
```

No framework. No annotations. The dependency is visible in the function signature and
verified by the compiler.

### Safe Mutual Dependencies

Most DI frameworks support circular dependencies — but at the cost of runtime errors
when objects are partially constructed. Context parameters make the same pattern safe:
both services are fully constructed before either can call the other.

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
    println("foo → bar")
    barService.bar()
  view ServiceFoo
end

class BarImpl
  def bar(): Unit =
    println("bar → foo")
    fooService.foo()
  view ServiceBar
end

def main =
  val foo = new FooImpl
  val bar = new BarImpl
  with fooService = foo, barService = bar in foo.foo()
```

`FooImpl` and `BarImpl` are fully initialized before `foo.foo()` is called. The
`with` clause wires them together at that single call site. There is no partially
constructed state and no framework magic.
