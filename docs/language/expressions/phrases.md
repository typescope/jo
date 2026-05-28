# Phrases

A phrase is an element that may appear in a block. Every open expression is a valid phrase. Phrases also include several constructs that are only valid at block level.

## Expressions as Phrases

Any expression — a word sequence, colon call, dot chain, lambda, open `if`, `match`, `allow`, `with`, or `rescue` — is a valid phrase. These are documented in [Expression Forms](expression-forms.md).

```jo
println "hello"           // word sequence
println: "hello"          // colon call
[1, 2, 3].size.toString   // word sequence (select chain)
if x > 0 then println x   // open if
```

## Assignment

```
(name | select | bracket_apply) "=" block
```

Assignments are statements — they do not produce values.

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

The right-hand side of an assignment starts a block:

```jo
result =
  val temp = compute()
  temp * 2
```

## Return, Break, Continue

`return` exits the enclosing function immediately. It is not valid inside lambdas:

```jo
def abs(n: Int): Int =
  if n < 0 then return -n
  n

def printPositive(n: Int): Unit =
  if n <= 0 then return
  println n
```

`break` and `continue` control the nearest enclosing loop:

```jo
for x in [1, 2, 3, 4, 5] do
  if x == 2 then continue
  if x == 4 then break
  println x
```

See [Control Flow](control-flow.md) for `while` and `for`.

## Local Definitions

Definitions are statements — they do not produce values.

### Value Definitions

```jo
val immutable = 42
var mutable = "can change"
val Point(x, y) = origin     // pattern value definition
```

### Pattern Definitions

```jo
pattern Even: Int =
  case x if x % 2 == 0

pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
```

Local pattern definitions introduce named patterns usable in `match` cases and `is` expressions within the same block. See [Pattern Definitions](../definitions/pattern-definitions.md) for the full syntax.

### Function Definitions

```jo
def greet(name: String): String = "Hello, " + name

def processData(data: List[Int]): Unit receives logger =
  logger.info("Processing")
```

See [Definitions](../definitions/overview.md) for the full definition syntax.

## Rescue Expression

    rescue ::= atom "rescue" simple_pattern "=>" block

The rescue expression handles error branches of a union type inline. The pattern must match exactly one of the two branches of a two-branch union type. If the success branch type defines a parameterless `.success` method, the result is automatically unwrapped to the payload.

```jo
def parseWithFallback(s: String): Int =
  parseNum(s) rescue Err(msg) =>
    println ("parse failed: " + msg)
    0

def getHost(config: Option[String]): String =
  config rescue None => "localhost"
```

See [Error Model](../error-model.md) for the full specification.

## See Also

- [Expression Forms](expression-forms.md) — Open expressions, which are also valid phrases
- [Blocks](blocks.md) — Collections of phrases
- [Control Flow](control-flow.md) — `if`, `match`, `while`, `for`, `break`, `continue`
- [Definitions](../definitions/overview.md) — Definition forms
