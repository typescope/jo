# Value Definitions

Value definitions create named bindings for values. Jo provides both immutable (`val`) and mutable (`var`) value definitions.

## Immutable Values (val)

Immutable values cannot be reassigned after initialization:

```jo
val count = 42
val message = "Hello"
val numbers = [1, 2, 3]

// count = 43  // ❌ Error - cannot reassign val
```

## Mutable Variables (var)

Mutable variables can be reassigned:

```jo
var counter = 0
var status = "pending"

counter = counter + 1  // ✓ OK
status = "complete"    // ✓ OK
```

## Type Annotations

Types can be explicitly specified:

```jo
val typed: String = "explicitly typed"
var counter: Int = 0

// Helpful for disambiguation
val result: Option[Int] = Some(42)
val list: List[String] = []
```

## Type Inference

Types are inferred when not specified:

```jo
val number = 42              // Inferred as Int
val text = "hello"           // Inferred as String
val flag = true              // Inferred as Bool
val items = [1, 2, 3]        // Inferred as List[Int]
val pair = Pair("a", 1)      // Inferred as Pair[String, Int]
```

## Block Values

Value definitions can have block bodies:

```jo
val result =
  val temp1 = compute()
  val temp2 = process(temp1)
  transform(temp2)

var state =
  val initial = getInitial()
  validate(initial)
```

## Scope

Value definitions can only appear inside function bodies, not at the top level of a namespace or section.

```jo
// ❌ Error - val at top level
val globalCount = 0

section MySection
  // ❌ Error - val at section level
  val sectionValue = 10
end

// ✓ OK - val inside function
def example(): Unit =
  val localValue = 42
  var localVar = "can change"
```

## Usage in Functions

```jo
def process(data: List[Int]): Result =
  val filtered = data.filter(x => x > 0)
  val doubled = filtered.map(x => x * 2)
  var sum = 0

  for x in doubled do
    sum = sum + x
  end

  Ok(sum)
```

## Shadowing

Value definitions can shadow previous definitions in nested scopes:

```jo
def example(): Unit =
  val x = 10

  if condition then
    val x = 20  // Shadows outer x
    println(x)  // Prints 20
  end

  println(x)    // Prints 10
```

## Destructuring

Use case definitions for destructuring instead of val:

```jo
// ❌ Cannot destructure with val
// val (x, y) = point

// ✓ Use case definition instead
case Point(x, y) = point
println(x)
println(y)
```

## Examples

```jo
def calculateStats(numbers: List[Int]): Stats =
  // Immutable values
  val filtered = numbers.filter(x => x > 0)
  val count = filtered.length

  // Mutable accumulator
  var sum = 0
  for x in filtered do
    sum = sum + x
  end

  val average = sum / count

  // Complex computation with block
  val median =
    val sorted = filtered.sort()
    val mid = count / 2
    if count % 2 == 0 then
      (sorted[mid - 1] + sorted[mid]) / 2
    else
      sorted[mid]

  Stats(count, sum, average, median)

def processData(): Unit =
  var retries = 3
  var success = false

  while retries > 0 && !success do
    val result = tryProcess()
    match result
    case Ok(_) =>
      success = true
    case Err(_) =>
      retries = retries - 1
    end
  end
```

## Best Practices

### Prefer Immutability

Use `val` by default, `var` only when necessary:

```jo
// ✓ Good - immutable
val result = compute()

// ⚠ Use sparingly - mutable
var counter = 0
```

### Clear Names

Use descriptive names for values:

```jo
// ✓ Good
val validatedUsers = users.filter(u => u.isValid)
val totalPrice = items.map(_.price).sum

// ⚠ Less clear
val tmp = users.filter(u => u.isValid)
val x = items.map(_.price).sum
```

### Limit Scope

Keep value definitions close to their usage:

```jo
// ✓ Good - defined where used
def process(): Unit =
  val data = fetchData()
  validate(data)
  val transformed = transform(data)
  save(transformed)

// ⚠ Less clear - too far from usage
def process(): Unit =
  val data = fetchData()
  val transformed = transform(data)
  // ... many lines ...
  save(transformed)
```

## See Also

- [Case Definitions](case-definitions.md) - For destructuring values
- [Type Inference](../types/type-inference.md) - For type inference rules
- [Function Definitions](function-definitions.md) - Where value definitions appear
