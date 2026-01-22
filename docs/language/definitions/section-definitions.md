# Section Definitions

Sections organize related definitions into named groups, providing a namespace for functions, types, and other definitions.

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

## See Also

- [Name Universes](../concepts/names.md) - Namespace system
- [Function Definitions](function-definitions.md) - Defining functions in sections
- [Type Definitions](type-definitions.md) - Defining types in sections
