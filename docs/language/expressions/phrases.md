# Phrases

A phrase is an element that may appear in a block. Every open expression is a valid phrase. Phrases also include several constructs that are only valid at block level.

## Expressions as Phrases

Any expression — a word sequence, colon call, dot chain, lambda, open `if`, `match`, `allow`, or `with` — is a valid phrase. These are documented in [Expression Forms](expression-forms.md).

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

### Function Definitions

```jo
def greet(name: String): String = "Hello, " + name

def processData(data: List[Int]): Unit receives logger =
  logger.info("Processing")
```

See [Definitions](../definitions/overview.md) for the full definition syntax.

## See Also

- [Expression Forms](expression-forms.md) — Open expressions, which are also valid phrases
- [Blocks](blocks.md) — Collections of phrases
- [Control Flow](control-flow.md) — `if`, `match`, `while`, `for`, `break`, `continue`
- [Definitions](../definitions/overview.md) — Definition forms
