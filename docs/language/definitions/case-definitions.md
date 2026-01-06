# Case Definitions

A case definition uses a pattern to destructure the value produced by the block expression. All variables bound by the pattern become available in the enclosing scope.

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

## Pattern Forms

Any simple pattern can be used (but not guards or assignments):

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

!!!warning "Only simple patterns"

    Only a simple pattern is allowed. Guards and assignments are not allowed
    in case definitions.

    ```jo
    case Point x y = ...           // ✓ OK

    case Point x y if x > 0 = ...  // ❌ Error - guard not allowed

    case Point x y then x = ... = ... // ❌ Error - assignment not allowed
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

## Exhaustivity Check

The compiler performs exhaustivity checking on case definitions to detect patterns that may fail at runtime:

```jo
// Warning: inexhaustive pattern
case Some(x) = getOption()
// Warning: None not covered - may fail at runtime

// Warning: inexhaustive pattern
case [head, ..tail] = getList()
// Warning: empty list not covered - may fail at runtime

// No warning - exhaustive
case Point(x, y) = getPoint()
// OK if getPoint() returns Point type
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
