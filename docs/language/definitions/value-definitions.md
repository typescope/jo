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

**Lambda Capture Restriction:**

Mutable variables cannot be captured inside lambdas:

```jo
var counter = 0

// ❌ Error - cannot capture mutable variable
val increment = () => counter = counter + 1

// ✓ OK - immutable values can be captured
val x = 10
val getX = () => x
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

## See Also

- [Pattern Value Definitions](pattern-value-definitions.md) - For destructuring values
- [Type Inference](../types/type-inference.md) - For type inference rules
- [Function Definitions](function-definitions.md) - Where value definitions appear
