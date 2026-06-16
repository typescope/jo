# Types

Jo's type system supports both functional and object-oriented programming paradigms.

## Type Categories

### [Basic Types](basic-types.md)
Primitive types (`Bool`, `Int`, `Char`, `Float`, `String`, `Unit`) and
commonly used standard library types (`Option`, `Result`, `List`, `Map`, `Set`).

### [Lambda Types](lambda-types.md)
Function types that specify parameter types, return types, and context parameter
requirements.

### [Named Types](named-types.md)
Class types, interface types, type aliases, and type parameters. Covers subtyping via
views, generic invariance, and alias transparency.

### [Union Types](union-types.md)
Values that can be one of several class type alternatives, enabling algebraic data types.

### [Extension Types](extension-types.md)
Types enriched with a fixed set of extension methods, enabling dot syntax on types
(such as union types) that have no members of their own.

### [Duck Types](duck-types.md)
Types that accept arguments with automatic conversion through a declared adapter list.

### [Annotation Types](annotation-types.md)
Types with compiler or backend hints attached; transparent to the type system.

### [Type Inference](type-inference.md)
Local, bidirectional type inference rules.

### [Type Adaptation](type-adaptation.md)
How the compiler converts a fully typed value to a fully known expected type.
