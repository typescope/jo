# Phrases

A phrase is a syntactic element that may appear in a block:

```
phrase ::= indented_expr | lambda | assignment | definition | control_flow | allow_clause
```

## Expression Phrases

Expressions are phrases that produce values:

```jo
42
x + y
list.map(x => x * 2)
if condition then a else b
```

## Assignment

```
assignment ::= lhs "=" block
lhs ::= identifier | selection | bracket_application
```

Assignments are statements (do not produce values).

### Simple Assignment

```jo
x = 10
count = count + 1
isValid = false
```

### Field Assignment

```jo
point.x = 20
user.name = "Alice"
config.timeout = 60
```

### Array/Map Assignment

```jo
array[0] = 42
map["key"] = "value"
matrix[i, j] = 0
```

### Block Assignment

The right-hand side of assignment starts a block:

```jo
result =
  val temp = compute()
  temp * 2

config =
  val host = getHost()
  val port = getPort()
  Config(host, port)
```

## Definitions

```
definition ::= val_def | var_def | fun_def | pattern_def | type_def
```

Definitions are statements. See [Definitions](../definitions/overview.md) for details.

### Value Definitions

```jo
val immutable = 42
var mutable = "can change"
```

### Function Definitions

```jo
def greet(name: String): String = "Hello, " + name

def processData(data: List[Int]): Unit receives logger =
  logger.info("Processing")
```

### Type Definitions

```jo
type UserId = Int
type Handler = String => Unit
```

## Control Flow

Control-flow constructs are documented in a dedicated page:

- [Control Flow](control-flow.md)

```
control_flow ::= if | match | return | while | for | break | continue
```

## Allow Clause

```
allow_clause ::= "allow" qualid {"," qualid} "in" block
```

The allow clause specifies the capabilities permitted for the body block. It is a phrase-level construct that scopes over its body.

```jo
// Allow specific capabilities
allow IO, network in
  process()

// Allow multiple capabilities
allow fileSystem, database, network in
  sync()

// Disallow all capabilities
allow none in compute()

// Allow with context parameter override
allow none in
  lineCount() with readLine = readLineFun
```

## See Also

- [Blocks](blocks.md) - Collections of phrases
- [Control Flow](control-flow.md) - `if`, `match`, `while`, `for`, `break`, and `continue`
- [Pattern Language](../patterns/overview.md) - Pattern matching details
- [Definitions](../definitions/overview.md) - Definition forms
