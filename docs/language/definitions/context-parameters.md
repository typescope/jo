# Context Parameters

Context parameters are named values declared at namespace level that propagate
automatically through function calls without appearing in every signature.

## Syntax

### Declaration

```
param_decl = "param" ident ":" type ["=" expr]
```

```jo
param indent: Int
param pageWidth: Int = 80
param connection: Connection
```

### Binding: `with`

```
with_clause = "with" ident "=" expr {"," ident "=" expr} "in" expr
```

Binds one or more context parameters for the duration of the expression:

```jo
with indent = 5 in line("hello")
with alpha = 3, beta = 6 in foo(10)
```

### Receives Annotation: `receives`

```
receives_clause = "receives" (ident {"," ident} | "none")
```

Used in function and lambda type signatures:

```jo
def pretty(doc: Doc): String receives pageWidth = ...
type Printer = Doc => String receives pageWidth
def pure(x: Int): Int receives none = x * 2
```

### Capability Control: `allow`

```
allow_clause = "allow" (qualid {"," qualid} | "none") "in" block
```

Restricts which context parameters the block may access:

```jo
allow connection in
  with maxResultCount = 200 in search(keyword)

allow none in test()
```

## Rules

### Binding and Propagation

- A `with` binding is active for the duration of its inner expression and propagates
  automatically to all functions called within it.
- Inner bindings shadow outer bindings (stack discipline).
- A context parameter must be bound — directly or via an outer `with` — before any
  function that uses it is called. Violations are reported at compile time with a
  dependency trace.

### Optional Parameters

`param name: T = rhs` desugars to a declaration plus a default definition:

```jo
param name: T
def name$default: T = rhs
```

The default is used when no binding is in scope. The default expression cannot
reference other context parameters.

### Lambdas and Capture

By default, a lambda captures context parameters at its creation site, like any other
variable:

```jo
param a: Int
val f = with a = 5 in makeFun(3)  // a = 5 captured in f
f(20)                               // no `with` needed — a already captured
```

To defer binding to the **call site**, declare the lambda type with `receives`:

```jo
type Printer = Doc => String receives pageWidth

def createPrinter(): Printer receives none =
  (doc: Doc) => pretty(doc)  // pageWidth NOT captured

val printer = createPrinter()
with pageWidth = 100 in printer("hello")  // pageWidth bound here
```

### Capability Control

`allow ps in block` restricts the block to only the listed parameters. Any use of an
unlisted parameter in the call chain is a compile-time error. `allow none` disallows all
context parameters from the enclosing scope.

## Error Messages

**Missing binding:**
```
Context parameter not provided: connection

The following is the trace that leads to the problem:
├── def main = getUsers()         [ app.jo:7:12 ]
├── def getUsers() = query(...)   [ app.jo:5:22 ]
└──     connection.execute(sql)   [ app.jo:3:29 ]
```

**Disallowed parameter:**
```
Parameter not allowed: logger

The following is the trace that leads to the problem:
├──   allow connection in query(sql)                  [ app.jo:7:3 ]
└── def query(sql) = ...connection...logger...        [ app.jo:4:42 ]
```
