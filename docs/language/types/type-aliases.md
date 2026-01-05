# Type Aliases

Type aliases are alternative names for types, created using type definitions.

## Overview

A type alias is completely transparent to the type system—the alias and its underlying type are indistinguishable during type checking and at runtime.

```jo
type UserId = Int

val id: UserId = 42
val num: Int = id    // OK: UserId and Int are the same type
```

## Properties

### Substitutability

Type aliases are **substitutable**—an alias can be used wherever its underlying type is expected, and vice versa:

```jo
type EmailAddress = String
type UserName = String

def sendEmail(to: EmailAddress, subject: String): Unit = ...

val name: UserName = "Alice"
sendEmail(name, "Welcome")  // OK: UserName = String = EmailAddress
```

This property means that multiple aliases for the same type are mutually compatible:

```jo
type A = Int
type B = Int

val a: A = 1
val b: B = a  // OK: both are Int
```

### Transparency

Type aliases have **no runtime representation**. They exist only during compilation for type checking and are erased afterwards:

```jo
type Temperature = Float
type Distance = Float

val temp: Temperature = 98.6
val dist: Distance = temp  // OK at runtime: both are Float
```

This differs from wrapper classes, which create distinct types:

```jo
class Temperature(value: Float)
class Distance(value: Float)

val temp = Temperature(98.6)
val dist: Distance = temp  // Error: different types
```

## See Also

- [Type Definitions](../definitions/type-definitions.md) - Syntax and semantics
- [Class Types](class-types.md) - For type-safe wrappers
- [Union Types](union-types.md) - For sum types
