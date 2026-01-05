# Type Definitions

Type definitions create aliases for existing types or define new types.

## Syntax

```jo
type TypeName = ExistingType
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

## Lambda Type Aliases

Simplify function types:

```jo
type Handler = (String, Int) => Unit
type Processor = String => String receives IO
type EventCallback = () => Unit receives logger
type Predicate[T] = T => Bool
```

## Generic Type Aliases

Type aliases can be generic:

```jo
type Result[T] = Either[String, T]
type AsyncResult[T] = () => Result[T] receives IO
type Mapper[T, R] = T => R
type Optional[T] = Option[T]
```

## Union Type Aliases

Create aliases for union types:

```jo
union Status = Success | Warning | Error
type Result = Status

union Option[T] = Some(value: T) | None
type Maybe[T] = Option[T]
```

## Complex Type Aliases

Simplify nested or complex types:

```jo
// Nested generic types
type UserCache = Map[UserId, User]
type EventLog = List[Pair[Timestamp, Event]]

// Function types with effects
type DatabaseQuery[T] = String => Result[T] receives database, logger
type IOAction[T] = () => T receives IO
```

## Usage Examples

### Domain Modeling

```jo
type CustomerId = Int
type ProductId = Int
type Quantity = Int
type Price = Float

type Order = List[Pair[ProductId, Quantity]]
type Invoice = Pair[CustomerId, Price]
```

### API Design

```jo
type RequestHandler = Request => Response receives IO, logger
type Middleware = RequestHandler => RequestHandler
type Route = Pair[String, RequestHandler]
```

### Error Handling

```jo
type ErrorMessage = String
type Result[T] = Either[ErrorMessage, T]
type Validation[T] = T => Result[T]
```

## Benefits

### Improved Readability

```jo
// Without type alias
def process(id: Int, name: String): Unit = ...

// With type aliases
def process(id: UserId, name: UserName): Unit = ...
```

### Centralized Changes

```jo
// Change type in one place
type UserId = String  // Changed from Int

// All usages automatically updated
def getUser(id: UserId): User = ...
def deleteUser(id: UserId): Unit = ...
```

### Abstraction

```jo
// Hide implementation details
type SessionToken = String

def authenticate(token: SessionToken): User = ...
```

## Type Aliases vs New Types

Type aliases create alternative names, not distinct types:

```jo
type UserId = Int
type OrderId = Int

val userId: UserId = 42
val orderId: OrderId = userId  // ✓ OK - both are Int
```

For distinct types, use classes:

```jo
class UserId(value: Int)
class OrderId(value: Int)

val userId = UserId(42)
val orderId: OrderId = userId  // ❌ Error - different types
```

## Recursive Type Aliases

Type aliases can be recursive when combined with union types:

```jo
union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])
type IntTree = Tree[Int]

union List[T] = Cons(head: T, tail: List[T]) | Nil
type StringList = List[String]
```

## Examples

```jo
// Simple aliases
type Name = String
type Age = Int
type Email = String

// Function type aliases
type Validator[T] = T => Bool
type Transformer[T, R] = T => R
type Handler[T] = T => Unit receives logger

// Generic aliases
type Cache[K, V] = Map[K, V]
type Result[T] = Either[String, T]
type AsyncComputation[T] = () => T receives IO

// Domain-specific aliases
type UserId = Int
type ProductId = String
type Price = Float
type Quantity = Int

type ShoppingCart = Map[ProductId, Quantity]
type OrderTotal = Price

// Complex type aliases
type QueryBuilder[T] = String => Result[T] receives database
type ResponseHandler = Response => Unit receives IO, logger
type AuthenticatedAction = Request => Response receives auth, database, logger
```

## Best Practices

### Use Descriptive Names

```jo
// ✓ Good
type UserId = Int
type EmailAddress = String

// ⚠ Less clear
type Id = Int
type Str = String
```

### Group Related Types

```jo
section UserTypes
  type UserId = Int
  type UserName = String
  type UserEmail = String
  type UserRole = String

  type User = Record(
    id: UserId,
    name: UserName,
    email: UserEmail,
    role: UserRole
  )
end
```

### Document Complex Aliases

```jo
// Complex mapping from HTTP status codes to result types
type HttpResult[T] = Either[HttpError, T]

// Generic async operation with comprehensive error handling
type AsyncOperation[T] = () => HttpResult[T] receives IO, logger, network
```

## See Also

- [Type Aliases](../types/type-aliases.md) - Detailed type alias documentation
- [Lambda Types](../types/lambda-types.md) - Function type aliases
- [Union Types](../types/union-types.md) - Union type aliases
