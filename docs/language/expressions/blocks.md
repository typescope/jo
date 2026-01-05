# Blocks

A block is a sequence of phrases:

```
block ::= {phrase}
```

## Block Structure

Blocks contain multiple phrases, each on its own line or properly aligned:

```jo
// Simple block
val x = 10
val y = 20
x + y

// Block with mixed phrase types
val data = fetchData()
val validated = validate(data)
if validated then
  process(data)
else
  logError("Invalid data")
```

## Block-Starting Constructs

The following constructs automatically start blocks after their respective keywords:

### Control Flow

- `if` ... `then` - starts a block for the then-branch
- `if` ... `else` - starts a block for the else-branch
- `while` ... `do` - starts a block for the loop body
- `for` ... `do` - starts a block for the loop body
- `case` ... `=>` - starts a block for the case body
- `begin` - starts an explicit block, requires matching `end`

```jo
// If-then-else with blocks
if condition then
  val temp = compute()
  process(temp)
else
  val default = getDefault()
  use(default)
end

// While with block
while hasNext() do
  val item = next()
  process(item)
  log(item)
end

// Match cases with blocks
match value
case Some(x) =>
  val doubled = x * 2
  println(doubled)
case None =>
  println("no value")
end

// Begin block
begin
  val x = 10
  val y = 20
  x + y
end
```

!!!warning
    Unlike other constructs where `end` is optional, `begin` requires a matching `end` marker. This is intentional: the explicit use of `begin` signals the programmer's intent to explicitly mark a region of code, and a missing `end` would be inconsistent with that intent.

### Definitions

- `val` ... `=` - starts a block for the value definition
- `var` ... `=` - starts a block for the variable definition
- `def` ... `=` - starts a block for the function body
- `param` ... `=` - starts a block for the parameter default value

```jo
// Val with block
val result =
  val temp1 = step1()
  val temp2 = step2(temp1)
  step3(temp2)

// Def with block
def process(data: Data): Result =
  val validated = validate(data)
  if validated then
    Ok(transform(data))
  else
    Err("Invalid data")
```

### Assignments and Lambdas

- `=` - starts a block for assignment values
- `=>` - starts a block for lambda bodies

```jo
// Assignment with block
count =
  val current = getCount()
  current + 1

// Lambda with block
val processor = data =>
  val cleaned = clean(data)
  val validated = validate(cleaned)
  transform(validated)
```

!!!info
    Pattern definitions (`pattern` ... `=`) do not start blocks. Their right-hand side consists of case patterns, which are not expressions and therefore not organized into blocks.

## Block Delimiters

Blocks are delimited by the line indentation of delimiters:

- For control flow and definitions, the block delimiter is the first keyword (`if`, `while`, `begin`, `val`, `var`, `def`, `param`)
- For assignments and lambdas, the block delimiter is the operator (`=`, `=>`)

The usual [offside rule](https://en.wikipedia.org/wiki/Off-side_rule) applies: a block continues while phrases remain at greater indentation than the delimiter, and ends when indentation returns to or before the delimiter's level.

```jo
// Block delimiter is 'val'
val result =
  val x = 10    // Indented - part of block
  val y = 20    // Indented - part of block
  x + y         // Indented - part of block
val other = 5   // Not indented - outside block

// Block delimiter is 'if'
if condition then
  doSomething()   // Indented - part of then block
  doMore()        // Indented - part of then block
else
  doOther()       // Indented - part of else block
end

// Block delimiter is '=>'
val f = x =>
  val doubled = x * 2    // Indented - part of lambda block
  doubled + 1            // Indented - part of lambda block
```

### Vertical Alignment

All phrases in a block must be vertically aligned:

```jo
// ✓ Good - all phrases aligned
val result =
  val x = 10
  val y = 20
  x + y

// ❌ Bad - misaligned phrases
val result =
  val x = 10
    val y = 20  // Error: misaligned
  x + y
```

## Block Values

A block is always an expression. Its value is determined by its final phrase:

- If the final phrase is an expression, the block evaluates to that value
- If the final phrase is a statement, a unit value is synthesized

```jo
// Block value is the final expression
val sum =
  val x = 10
  val y = 20
  x + y        // Block value is 30

// Block value is unit (final phrase is statement)
val result =
  println("start")
  process()
  println("done")  // Block value is unit

// Block value from if expression
val status =
  val code = getCode()
  if code == 200 then
    "success"
  else
    "failure"
```

## Nested Blocks

Blocks can be nested within control flow and definitions:

```jo
def process(data: Data): Result =
  val validated =
    val cleaned = clean(data)
    validate(cleaned)

  if validated then
    val transformed =
      val normalized = normalize(data)
      transform(normalized)
    Ok(transformed)
  else
    Err("Invalid")
```

## Block Scope

Variables defined in a block are scoped to that block:

```jo
val outer = 10

val result =
  val inner = 20      // Scoped to this block
  outer + inner       // Can access outer and inner

// inner is not accessible here
// outer is still accessible
```

## Examples

```jo
// Simple block in function
def factorial(n: Int): Int =
  if n <= 1 then
    1
  else
    n * factorial(n - 1)

// Complex block with multiple constructs
def processUsers(users: List[User]): Unit receives logger, database =
  logger.info("Processing users")

  for user in users do
    val validated =
      val name = user.name.trim()
      val email = user.email.toLowerCase()
      validate(name, email)

    if validated then
      database.save(user)
      logger.info("Saved: " + user.name)
    else
      logger.error("Invalid: " + user.name)
    end
  end

  logger.info("Done processing")

// Block as expression value
val config =
  val host = getEnv("HOST")
  val port = getEnv("PORT").toInt
  val timeout = 30
  Config(host, port, timeout)
```

## See Also

- [Phrases](phrases.md) - Elements of blocks
- [Terms](terms.md) - Multiline term continuation
- [Syntax Summary](../syntax-summary.md) - Complete grammar
