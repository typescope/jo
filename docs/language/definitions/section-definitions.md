# Section Definitions

Sections organize related definitions into named groups, providing a namespace for functions, types, and other definitions.

## Syntax

```jo
section SectionName
  definitions
end
```

## Basic Sections

Group related functionality:

```jo
section Database
  def connect(url: String): Connection = ...
  def query(sql: String): ResultSet = ...
  def close(conn: Connection): Unit = ...
end

section Validation
  def validateEmail(email: String): Bool = ...
  def validateAge(age: Int): Bool = ...
  def validatePassword(password: String): Bool = ...
end
```

## Accessing Section Members

Use qualified names to access section members:

```jo
section MathUtils
  def square(x: Int): Int = x * x
  def cube(x: Int): Int = x * x * x
end

// Access with qualified name
val result = MathUtils.square(5)
val volume = MathUtils.cube(3)
```

## Nested Sections

Sections can be nested:

```jo
section App
  section Database
    def connect(): Connection = ...
    def disconnect(): Unit = ...
  end

  section UI
    def render(): Unit = ...
    def update(): Unit = ...
  end
end

// Access nested sections
App.Database.connect()
App.UI.render()
```

## Section Contents

Sections can contain various definitions:

```jo
section UserManagement
  // Type definitions
  type UserId = Int
  type UserRole = String

  // Class definitions
  class User(id: UserId, name: String, role: UserRole)

  // Function definitions
  def createUser(name: String, role: UserRole): User = ...
  def deleteUser(id: UserId): Unit = ...
  def updateUser(user: User): Unit = ...

  // Pattern definitions
  pattern Admin: User =
    case u if u.role == "admin"
end
```

## Importing Section Members

```jo
import UserManagement.*

// Now can use without qualification
val user = createUser("Alice", "admin")
```

## Section Visibility

Sections provide a namespace boundary:

```jo
section Internal
  // Private helper
  def helper(): Unit = ...

  // Public API
  def publicAPI(): Unit =
    helper()  // Can call within section
end

// From outside
// Internal.helper()  // May not be accessible depending on visibility rules
Internal.publicAPI()  // OK
```

## Organization Patterns

### By Feature

```jo
section Authentication
  def login(user: String, password: String): Session = ...
  def logout(session: Session): Unit = ...
  def verify(session: Session): Bool = ...
end

section Authorization
  def hasPermission(user: User, resource: Resource): Bool = ...
  def grant(user: User, permission: Permission): Unit = ...
  def revoke(user: User, permission: Permission): Unit = ...
end
```

### By Domain

```jo
section Orders
  type OrderId = Int
  class Order(id: OrderId, items: List[Item], total: Price)

  def createOrder(items: List[Item]): Order = ...
  def cancelOrder(id: OrderId): Unit = ...
  def fulfillOrder(id: OrderId): Unit = ...
end

section Inventory
  type ProductId = Int
  class Product(id: ProductId, name: String, quantity: Int)

  def addProduct(product: Product): Unit = ...
  def removeProduct(id: ProductId): Unit = ...
  def updateQuantity(id: ProductId, quantity: Int): Unit = ...
end
```

### By Layer

```jo
section Repository
  def findUser(id: UserId): Option[User] = ...
  def saveUser(user: User): Unit = ...
  def deleteUser(id: UserId): Unit = ...
end

section Service
  def processUser(id: UserId): Result = ...
  def validateUser(user: User): Bool = ...
end

section Controller
  def handleGetUser(request: Request): Response = ...
  def handleCreateUser(request: Request): Response = ...
end
```

## Examples

### Math Utilities

```jo
section MathUtils
  def abs(x: Int): Int =
    if x < 0 then -x else x

  def max(a: Int, b: Int): Int =
    if a > b then a else b

  def min(a: Int, b: Int): Int =
    if a < b then a else b

  def clamp(value: Int, low: Int, high: Int): Int =
    max(low, min(value, high))
end
```

### String Operations

```jo
section StringUtils
  def trim(s: String): String = ...
  def split(s: String, delimiter: String): List[String] = ...
  def join(parts: List[String], separator: String): String = ...
  def capitalize(s: String): String = ...
  def lowercase(s: String): String = ...
  def uppercase(s: String): String = ...
end
```

### Configuration

```jo
section Config
  type Environment = String
  type Port = Int
  type Host = String

  class AppConfig(env: Environment, port: Port, host: Host)

  def fromFile(path: String): AppConfig receives open = ...
  def fromEnv(): AppConfig receives IO = ...
  def validate(config: AppConfig): Bool = ...
end
```

## Best Practices

### Clear Boundaries

Group related functionality:

```jo
// ✓ Good - cohesive section
section UserRepository
  def findById(id: UserId): Option[User] = ...
  def save(user: User): Unit = ...
  def delete(id: UserId): Unit = ...
end

// ⚠ Less cohesive - mixed concerns
section Utils
  def findUser(id: UserId): Option[User] = ...
  def formatDate(date: Date): String = ...
  def validateEmail(email: String): Bool = ...
end
```

### Avoid Deep Nesting

Keep section hierarchy shallow:

```jo
// ✓ Good - flat structure
section Database
  ...
end

section Cache
  ...
end

// ⚠ Avoid deep nesting
section App
  section Core
    section Database
      section Connection
        ...
      end
    end
  end
end
```

### Descriptive Names

Use clear, descriptive section names:

```jo
// ✓ Good
section UserAuthentication
section OrderProcessing
section EmailNotifications

// ⚠ Less clear
section Utils
section Helpers
section Misc
```

## See Also

- [Name Universes](../names.md) - Namespace system
- [Function Definitions](function-definitions.md) - Defining functions in sections
- [Type Definitions](type-definitions.md) - Defining types in sections
