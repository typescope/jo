# Phrases

A phrase is a syntactic element that may appear in a block:

```
phrase ::= expr_modified | assignment | definition | control_flow | allow_clause
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

### If

```
if ::= "if" term "then" block ["else" block] ["end"]
```

If constructs are expressions.

```jo
if x > 0 then
  println("positive")
end

if condition then
  result1
else
  result2
end

// If expressions produce values
val status = if x > 0 then "positive" else "negative"

// Nested if
if x > 0 then
  "positive"
else if x < 0 then
  "negative"
else
  "zero"
```

### Match

```
match ::= "match" term {case} ["end"]
case ::= "case" pattern "=>" block
```

Match constructs are expressions. See [Pattern Language](../patterns/overview.md) for pattern syntax.

```jo
match value
case Some(x) => println(x)
case None => println("no value")
end

// Match expressions produce values
val result = match status
  case Success => "ok"
  case Warning => "warning"
  case Error => "error"
end

// Multiple cases
match number
case 0 => "zero"
case 1 => "one"
case x if x > 0 => "positive"
case _ => "negative"
end
```

### While

```
while ::= "while" term "do" block ["end"]
```

While constructs are statements.

```jo
while hasNext() do
  val item = next()
  process(item)
end

var i = 0
while i < 10 do
  println(i)
  i = i + 1
end
```

### For

```
for ::= "for" expr_pattern "in" term ["if" term] "do" block ["end"]
```

For loops iterate over collections by pattern matching on each element.

The syntax desugars to:

```jo
val $iter = expr.iterator
while $iter.hasNext do
  val expr_pattern = $iter.next
  if cond then
    block
```

For loops are statements.

```jo
// Basic for loop
for x in [1, 2, 3, 4, 5] do
  println(x)
end

// Pattern matching in for loop
for Point(x, y) in points do
  println("x=" + x + ", y=" + y)
end

// With filtering
for x in numbers if x > 0 do
  println(x)
end

// Multiple pattern variables
for (key, value) in pairs do
  println(key + ": " + value)
end
```

#### Exhaustive Patterns

The pattern in a for loop must be exhaustive. The compiler warns about non-exhaustive patterns that could fail at runtime:

```jo
// Warning: non-exhaustive pattern (missing None)
for Some(x) in optionList do
  println(x)
end

// OK: exhaustive pattern - all lists have elements
for x in list do
  println(x)
end

// OK: filtering via guard condition
for Point(x, y) in points if x > 0 do
  println(x)
end
```

#### Filtering with Is Expressions

Use the optional `if` clause to filter elements. You can also use `is` expressions in the condition:

```jo
// Filter using is expression
for elem in mixedList if elem is Some(x) do
  println(x)
end

// Combine pattern matching and filtering
for Point(x, y) in points if x > 0 && y > 0 do
  println(x + y)
end
```

!!!note
    Pattern match failures in for loops cause runtime errors (like pattern value definitions). Use `is` expressions in the `if` clause for filtering instead of relying on non-exhaustive patterns.

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

## Phrase Examples

```jo
// Expression phrase
x + y

// Assignment phrase
count = count + 1

// Definition phrase
val result = compute()

// If phrase (expression)
if condition then
  doSomething()
else
  doOtherThing()
end

// Match phrase (expression)
match value
case Some(x) => x
case None => 0
end

// While phrase (statement)
while hasMore() do
  processNext()
end

// For phrase (statement)
for item in items do
  process(item)
end
```

## See Also

- [Blocks](blocks.md) - Collections of phrases
- [Control Flow](../patterns/overview.md) - Pattern matching details
- [Definitions](../definitions/overview.md) - Definition forms
