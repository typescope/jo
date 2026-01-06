# Type Definitions

Type definitions introduce new names for types using the `type` keyword.

## Syntax

```
type_def = "type" ident [type_params] "=" type
```

A type definition binds a name to a type expression. The name can optionally be parameterized with type parameters.

## Forms

### Simple Type Definitions

```jo
type UserId = Int
type EmailAddress = String
type Score = Float
```

### Generic Type Definitions

Type definitions can be parameterized:

```jo
type Result[T] = Either[String, T]
type Predicate[T] = T => Bool
type Mapper[A, B] = A => B
type AsyncResult[T] = () => Result[T] receives IO
```

Type parameters follow the same rules as class and function type parameters.

### Type Operators

Type definitions can use operator symbols as names, enabling infix or prefix type syntax:

```jo
// Infix type operator
type [S] ~ [T] = Pair[S, T]

val pair: Int ~ String = Pair(42, "hello")
val tuple: String ~ Bool ~ Int = Pair("x", Pair(true, 1))
```

```jo
// Prefix type operator for varargs
type ..[T] = List[T]

def sum(nums: ..Int): Int = ...  // Equivalent to: nums: List[Int]
```

Type parameters can appear in different positions:

- **Infix operators**: `type [A] OP [B] = ...` with parameters surrounding the operator
- **Prefix operators**: `type OP[T] = ...` with parameters after the operator

All type operators have equal precedence and are left-associative.

## Semantics

### Type Alias Transparency

Type definitions create **transparent aliases**—the defined name and the type expression are completely interchangeable:

```jo
type UserId = Int
type OrderId = Int

val userId: UserId = 42     // OK
val num: Int = userId       // OK: UserId = Int
val orderId: OrderId = userId  // OK: both are Int
```

This means:

- Type checking treats the alias and its definition identically
- No runtime distinction exists between an alias and its underlying type
- Multiple aliases for the same type are mutually compatible

### Type Parameters

Generic type definitions introduce type parameters:

```jo
type Result[T] = Either[String, T]

val x: Result[Int] = Right(42)        // T = Int
val y: Result[String] = Left("error") // T = String
```

Type parameters must be instantiated with concrete types when the alias is used.

### Recursive Definitions

Type definitions can be recursive when the type expression is a union type:

```jo
union List[T] = Cons(head: T, tail: List[T]) | Nil
type IntList = List[Int]

union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])
type StringTree = Tree[String]
```

Direct recursive aliases without union types are not allowed:

```jo
type Loop = Loop  // Error: infinite type
type Box = Pair[Int, Box]  // Error: infinite type
```

## See Also

- [Type Aliases](../types/type-aliases.md) - Semantic properties and use cases
- [Lambda Types](../types/lambda-types.md) - Function type syntax
- [Union Types](../types/union-types.md) - Recursive type definitions
