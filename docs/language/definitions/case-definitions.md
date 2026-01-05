# Case Definitions

A case definition uses a pattern to destructure the value produced by the block expression. All variables bound by the pattern become available in the enclosing scope.

## Syntax

```jo
case pattern = block
```

## Basic Usage

Destructure structured data:

```jo
// Tuple destructuring
case Point(x, y) = getPoint()
// x and y are now bound and available
println(x)
println(y)

// Class destructuring
case User(name, email, age) = getUser()
println("User: " + name)
println("Email: " + email)

// Nested destructuring
case Node(left, value, right) = tree.root
// left, value, and right are all bound
```

## Pattern Types

Any pattern expression can be used (but not guards or assignments):

```jo
// Simple variable
case x = compute()

// Type pattern
case user: User = getValue()

// Apply pattern
case Some(value) = maybeValue

// Bind pattern
case point @ Point(x, y) = getPoint()
// point, x, and y are all bound

// Sequence pattern
case [first, second, ..rest] = getList()
```

## Semantics

**Runtime Behavior:**

- If the pattern match fails, it is a runtime error
- All variables bound by the pattern are introduced into the current scope
- The block expression is evaluated once

```jo
// ✓ OK - pattern always matches
case Point(x, y) = Point(10, 20)

// ⚠ Runtime error if getValue() doesn't return Point
case Point(x, y) = getValue()
```

## Restrictions

!!!warning "Only pattern expression"

    Only a pattern expression is allowed. Guards and assignments are not allowed
    in case definitions.

    ```jo
    case Point x y = ...           // ✓ OK

    case Point x y if x > 0 = ...  // ❌ Error - guard not allowed

    case Point x y then x = ... = ... // ❌ Error - assignment not allowed
    ```

## Safe Alternatives

Use `match` expressions for safe destructuring:

```jo
// Unsafe - can fail at runtime
case Point(x, y) = getValue()

// Safe - handles all cases
match getValue()
case Point(x, y) =>
  // Use x and y
  process(x, y)
case _ =>
  // Handle non-Point values
  handleError()
end
```

## Common Patterns

### Pair Destructuring

```jo
case Pair(first, second) = getPair()
println("First: " + first)
println("Second: " + second)
```

### Option Unwrapping

```jo
// Unsafe - fails if None
case Some(value) = getOption()

// Better - safe with default
val value = match getOption()
  case Some(x) => x
  case None => defaultValue
end
```

### List Destructuring

```jo
// Get first element
case [head, ..tail] = getList()

// Get first two elements
case [first, second, ..rest] = getList()

// Fixed-size list
case [x, y, z] = getThreeElements()
```

### Nested Structures

```jo
case Tree(Leaf(x), value, Leaf(y)) = getTree()
// x, value, and y are all bound

case Response(Ok(Data(content))) = getResponse()
// content is bound
```

## Multiple Case Definitions

Multiple case definitions in sequence:

```jo
case Point(x, y) = getPoint()
case Config(host, port) = getConfig()

// Both x, y, host, and port are now available
connect(host, port)
drawAt(x, y)
```

## Use in Functions

```jo
def processUser(userId: Int): Unit =
  case User(name, email, age) = database.getUser(userId)

  println("Processing user: " + name)
  sendEmail(email)

  if age >= 18 then
    grantAccess()
  else
    requestParentalConsent()

def calculateDistance(): Float =
  case Point(x1, y1) = start
  case Point(x2, y2) = end

  val dx = x2 - x1
  val dy = y2 - y1
  sqrt(dx * dx + dy * dy)
```

## Error Handling

Case definitions throw runtime errors on match failure:

```jo
case Point(x, y) = "not a point"  // Runtime error!
```

For safer code, prefer:

1. **Match expressions** for fallible destructuring
2. **Type annotations** to ensure correct types
3. **Option types** to handle absence

```jo
// Safe with match
val coords = match getPointOrNull()
  case Point(x, y) => Some(Pair(x, y))
  case _ => None
end

// Safe with type constraint
def requirePoint(value: Point): Unit =
  case Point(x, y) = value  // OK - type guarantees match
  process(x, y)
```

## See Also

- [Pattern Language](../patterns/index.md) - For pattern syntax
- [Value Definitions](value-definitions.md) - For simple value bindings
- [Match Expressions](../expressions/phrases.md#match) - For safe destructuring
