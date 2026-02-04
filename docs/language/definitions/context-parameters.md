# Context Parameters

This document provides the precise specification for context parameters. For a high-level introduction, see [concepts/context-parameters](../concepts/context-parameters.md).

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

### The `with` Clause

```
with_clause = expr "with" ident "=" expr {"," ident "=" expr}
```

Binds context parameters for the duration of the expression:

```jo
line("hello") with indent = 5
foo(10) with alpha = 3, beta = 6
```

### The `receives` Clause

```
receives_clause = "receives" (ident {"," ident} | "none")
```

Used in function declarations and lambda types:

```jo
def pretty(doc: Doc): String receives pageWidth = ...
type Printer = String => String receives pageWidth
def pure(x: Int): Int receives none = x * 2
```

### The `allow` Clause

```
allow_clause = "allow" qualid {"," qualid} "in" block
```

Restricts which context parameters may be accessed:

```jo
allow connection in
  search(keyword) with maxResultCount = 200

allow none in test()
```

## Semantics

### Declaration and Scope

1. **Top-level declaration**: Context parameters are declared at the namespace/file level.
2. **Lexical scope**: Context parameters are visible within their lexical scope (namespace/file and imports).
3. **Identity**: Each context parameter declaration establishes a unique identity that prevents accidental name conflicts across modules.

### Binding and Propagation

1. **Remote binding**: Context parameters can be bound remotely using `with` clauses.
2. **Automatic propagation**: Bindings propagate automatically through function calls in the execution path.
3. **Shadowing**: Inner bindings shadow outer bindings following stack discipline.
4. **Stack-based extent**: Bindings follow a stack discipline — they are only valid during execution of the expression they bind.

### Optional Context Parameters

Context parameters can have default values:

```jo
param name: T = rhs
```

This desugars to:

```jo
param name: T
def name$default: T = rhs
```

The default value is automatically supplied in the scope when no binding is available.

**Restriction**: The default value expression cannot depend on other context parameters (to prevent cycles and semantic surprises).

### The `receives` Clause

The `receives` keyword serves multiple purposes:

1. **For 2nd-class functions (def)**: Declares context parameter dependencies explicitly.
2. **For lambda types**: Specifies parameters to be received from call site rather than captured.
3. **`receives none`**: Explicitly states no context parameters are needed.

```jo
// 2nd-class function declaration
def process(): Unit receives connection, logger = ...

// Lambda type - parameters NOT captured
type Handler = Request => Response receives connection

// Explicitly no dependencies
def pure(x: Int): Int receives none = x * 2
```

### The `allow` Clause

The `allow` clause provides fine-grained control over which context parameters may be accessed by the callee:

```jo
param maxResultCount: Int
param connection: Connection
param args: Array[String]

def search(keyword: String) = ...

def process =
  allow connection in
    search(keyword) with maxResultCount = 200
```

In this example:

- `search` can use `maxResultCount` (bound via `with`) and `connection` (allowed).
- `search` cannot use `args` — attempting to do so causes a compile error.

**Special form**: `allow none` disallows all context parameters from enclosing context:

```jo
def test = allow none in factorial(10)  // factorial cannot use any context parameters
```

### Lambdas and Capture

Lambdas capture context parameters by default at their creation site:

```jo
param a: Int
param b: Int

def makeFun(x: Int) = (n: Int) => n + a * bar(x)

def bar(x: Int): Int = x * b

def main =
  val f = makeFun(3) with a = 5, b = 10
  f(20)  // No context parameters needed - all captured
```

To defer context parameter binding to the call site instead of capture site, use `receives` in the lambda type:

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

### Static Checking

Context parameters are tracked statically. The compiler ensures:

1. **Binding before usage**: A context parameter must be bound before it can be used.
2. **Completeness check**: The compiler verifies that all required context parameters are available at every call site.

Example of a compile-time error:

```jo
param connection: Connection

def query(sql: String): List[Row] = connection.execute(sql)

def getUsers(): List[Row] = query("SELECT * FROM users")

def main = getUsers()  // Error: Context parameter not provided: connection
```

Error message includes a trace showing the dependency chain:

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

### Capability Control

```jo
param connection: Connection
param maxResults: Int
param logger: Logger

def search(query: String): List[Result] receives connection, maxResults = ...

def process(query: String) =
  // search can use connection and maxResults, but NOT logger
  allow connection, maxResults in search(query)
```
