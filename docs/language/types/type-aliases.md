# Type Aliases

Type aliases create alternative names for existing types, improving code clarity and expressiveness.

## Syntax

```jo
type AliasName = ExistingType
```

## Basic Type Aliases

Create meaningful names for primitive types:

```jo
type UserId = Int
type UserName = String
type ConnectionString = String
type Age = Int
type Score = Float
```

## Function Type Aliases

Simplify complex function types:

```jo
type EventHandler[T] = T => Unit receives logger
type Validator[T] = T => Bool
type Transformer[T, R] = T => R receives IO
type AsyncCallback = () => Unit receives IO
```

## Generic Type Aliases

Type aliases can be generic:

```jo
type Result[T] = Either[String, T]
type AsyncResult[T] = () => Result[T] receives IO
type Predicate[T] = T => Bool
type Mapper[T, R] = T => R
```

## Complex Type Aliases

Simplify nested or complex types:

```jo
// Nested generic types
type UserCache = Map[UserId, User]
type EventLog = List[Pair[Timestamp, Event]]

// Union type alias
type HttpResponse = Success | Redirect | ClientError | ServerError

// Lambda type with effects
type DatabaseQuery[T] = String => Result[T] receives database, logger
```

## Benefits

### Improved Readability

Type aliases make code more self-documenting:

```jo
// Without type alias
def process(id: Int, name: String, connection: String): Unit = ...

// With type aliases
def process(id: UserId, name: UserName, connection: ConnectionString): Unit = ...
```

### Centralized Type Definitions

Change types in one place:

```jo
// Define once
type UserId = Int

// Use everywhere
def getUser(id: UserId): User = ...
def deleteUser(id: UserId): Unit = ...
def updateUser(id: UserId, user: User): Unit = ...

// Easy to change to String later
type UserId = String  // Update in one place
```

### Abstraction

Hide implementation details:

```jo
// Hide the internal representation
type SessionToken = String

// Clients don't need to know it's a String
def authenticate(token: SessionToken): User = ...
```

## Type Aliases vs New Types

Type aliases create alternative names, not new types. The alias and original type are interchangeable:

```jo
type UserId = Int

val id: UserId = 42  // OK - Int is compatible with UserId
val num: Int = id    // OK - UserId is compatible with Int
```

For truly distinct types, use classes:

```jo
class UserId(value: Int)
class OrderId(value: Int)

val userId = UserId(42)
val orderId = OrderId(42)
// userId and orderId are different types, not interchangeable
```

## Recursive Type Aliases

Type aliases can be recursive when combined with union types:

```jo
union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])
type IntTree = Tree[Int]

union List[T] = Cons(head: T, tail: List[T]) | Nil
type StringList = List[String]
```

## See Also

- [Lambda Types](lambda-types.md) - For function type aliases
- [Union Types](union-types.md) - For union type aliases
- [Type Definitions](../definitions/type-definitions.md) - For syntax details
