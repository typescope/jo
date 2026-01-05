# Class Definitions

Classes define new types with fields and methods. Jo supports data classes with automatic constructors and full classes with explicit constructors and mutable state.

## Syntax

```jo
// Data class (automatic constructor)
class ClassName(field1: Type1, field2: Type2)

// Class with body
class ClassName
  field declarations
  constructor definitions
  method definitions
end

// Class with parameters and methods
class ClassName(field1: Type1, field2: Type2)
  method definitions
end
```

## Data Classes

Simple classes with constructor parameters automatically create data classes:

```jo
class Point(x: Int, y: Int)
class Config(host: String, port: Int, timeout: Int)
class User(id: Int, name: String, email: String)

// Automatic constructor
val point = Point(10, 20)
val config = Config("localhost", 8080, 30)
```

Data classes automatically generate:
- Constructor functions
- Pattern definitions for pattern matching

```jo
class Point(x: Int, y: Int)

// Use in pattern matching (automatically generated)
match point
case Point(x, y) => println("x=" + x + ", y=" + y)
end
```

## Classes with Methods

Add methods to classes with parameters:

```jo
class Rectangle(width: Int, height: Int)
  def area: Int = width * height
  def perimeter: Int = 2 * (width + height)
  def isSquare: Bool = width == height
end

val rect = Rectangle(5, 10)
println(rect.area)       // 50
println(rect.perimeter)  // 30
println(rect.isSquare)   // false
```

## Explicit Constructors

Define constructors manually using a method named after the class:

```jo
class Person
  val name: String
  val age: Int

  def Person(name: String, age: Int) =
    this.name = name
    this.age = age

  def greet: String = "Hello, I'm " + name
  def isAdult: Bool = age >= 18
end

val person = Person("Alice", 30)
```

## Mutable Fields

Classes can have mutable state using `var` fields:

```jo
class Counter
  var count: Int

  def Counter(initial: Int) =
    this.count = initial

  def increment() =
    this.count = this.count + 1

  def decrement() =
    this.count = this.count - 1

  def get() = this.count
end

val counter = Counter(0)
counter.increment()
counter.increment()
println(counter.get())  // 2
```

## Generic Classes

Classes can be parameterized with type parameters:

```jo
class Box[T](value: T)
  def map[U](f: T => U): Box[U] = Box(f(value))
  def get: T = value
end

val intBox = Box(42)
val stringBox = intBox.map(x => x.toString)

class Pair[A, B](first: A, second: B)
  def swap: Pair[B, A] = Pair(second, first)
end
```

## Methods with Effects

Methods can declare effect requirements:

```jo
class Logger
  def log(message: String): Unit receives IO.stdout =
    val timestamp = getCurrentTime()
    val entry = timestamp + ": " + message + "\n"
    println entry
end
```

## Implementing Interfaces Through Views

Classes implement interfaces using the view mechanism:

```jo
interface Drawable
  def draw(): Unit receives IO.stdout
end

class Circle(radius: Int)
  def area: Float = 3.14159 * radius * radius

  def draw(): Unit receives IO.stdout =
    println("Circle with radius " + c.radius)

  view Drawable
end
```

## Examples

### Data Model

```jo
class Order(id: Int, customerId: Int, items: List[Item], total: Float)

class Customer(id: Int, name: String, email: String)

class Product(id: Int, name: String, price: Float, stock: Int)
```

### With Business Logic

```jo
class ShoppingCart
  var items: List[Product ~ Int]

  def ShoppingCart() =
    this.items = []

  def addItem(product: Product, quantity: Int): Unit =
    this.items = [product ~ quantity, ..this.items]

  def removeItem(productId: Int): Unit =
    this.items = this.items.exclude begin pair =>
      val Pair(product, _) = pair
      product.id != productId
    end

  def total(): Float =
    var sum = 0.0
    for Pair(product, quantity) in this.items do
      sum = sum + product.price * quantity

    sum
end
```

### Stateful Component

```jo
class ConnectionPool
  var connections: List[Connection]
  var maxSize: Int

  def ConnectionPool(maxSize: Int) =
    this.connections = []
    this.maxSize = maxSize

  def acquire(): Option[Connection] receives IO =
    match this.connections
    case [conn, ..rest] =>
      this.connections = rest
      Some(conn)
    case [] if this.connections.length < this.maxSize =>
      val conn = createConnection()
      Some(conn)
    case [] =>
      None

  def release(conn: Connection): Unit =
    this.connections = [conn, ..this.connections]

  def createConnection(): Connection receives IO =
    // Create new connection
    ...
end
```

### Generic Container

```jo
class Stack[T]
  var items: List[T]

  def Stack() =
    this.items = []

  def push(item: T): Unit =
    this.items = [item, ..this.items]

  def pop(): Option[T] =
    match this.items
    case [head, ..tail] =>
      this.items = tail
      Some(head)
    case [] =>
      None

  def peek(): Option[T] =
    match this.items
    case [head, .._] => Some(head)
    case [] => None

  def isEmpty(): Bool = this.items.isEmpty
  def size(): Int = this.items.length
end
```

## Data Classes vs Full Classes

| Feature | Data Class | Full Class |
|---------|-----------|-----------|
| Syntax | Parameters only | Explicit body |
| Constructor | Automatic | Manual |
| Pattern matching | Auto-generated | Manual |
| Mutability | Immutable fields | Can have `var` fields |
| Methods | Can add | Can add |

## Best Practices

### Prefer Immutability

```jo
// ✓ Good - immutable data class
class Point(x: Int, y: Int)

// ⚠ Use mutable state sparingly
class Point
  var x: Int
  var y: Int
end
```

### Clear Constructors

```jo
// ✓ Good - clear initialization
class User(id: Int, name: String, email: String)

class Database
  val connection: Connection

  def Database(url: String, timeout: Int) =
    this.connection = connect(url, timeout)
end
```

### Encapsulation

```jo
class BankAccount
  private var balance: Float

  def BankAccount(initialBalance: Float) =
    this.balance = initialBalance

  def deposit(amount: Float): Unit =
    if amount > 0 then
      this.balance = this.balance + amount

  def withdraw(amount: Float): Bool =
    if amount > 0 && amount <= this.balance then
      this.balance = this.balance - amount
      true
    else
      false

  // Don't expose balance directly - provide accessor
  def getBalance(): Float = this.balance
end
```

## See Also

- [Class Types](../types/class-types.md) - Class type system
- [Interface Definitions](interface-definitions.md) - Implementing interfaces
- [Algebraic Data Types](adt.md) - Union type definitions
