# Types

_Draft: Work in Progress_

Jo features a rich type system that supports both functional and object-oriented programming paradigms.

## Type Categories

Jo provides several categories of types:

### [Basic Types](basic-types.md)
Beyond primitive types such as Bool and Int, Jo provides foundational types for building applications.

### [List Types](list-types.md)
Immutable lists with efficient O(1) prepend, append, and concat operations.

### [Lambda Types](lambda-types.md)
Function types that specify parameter types, return types, and effect requirements with support for context parameters.

### [Class Types](class-types.md)
Structured data types defined by class definitions, with rules for subtyping and generic instantiation.

### [Union Types](union-types.md)
Values that can be one of several alternatives, enabling algebraic data types.

### [Type Aliases](type-aliases.md)
Alternative names for existing types to improve code clarity.

### [View Types](view-types.md)
Extend existing types with additional interfaces without modifying original definitions.

### [Duck Types](duck-types.md)
Flexible parameter types that accept arguments with automatic conversion.

### [Type Inference](type-inference.md)
Type inference reduces the need for explicit annotations.

## Quick Examples

```jo
// Basic types
val flag: Bool = true
val count: Int = 42

// List types
val numbers = [1, 2, 3]

// Lambda types
type Handler = (String, Int) => Unit
type Processor = String => String receives IO

// Union types (with generics)
union Option[T] = Some(value: T) | None

// Type aliases
type UserId = Int

// Context parameters and effects
def process(data: List[Int]): Unit receives logger =
  logger.info("Processing started")
```
