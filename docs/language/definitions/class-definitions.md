# Class Definitions

Classes define new types with fields and methods. Jo supports data classes with automatic constructors and full classes with explicit constructors and mutable state.

## Syntax

```
class_def = "class" ident [type_params] [params] {class_member} ["end"]
class_member = view_decl | field | method
field = ("val" | "var") ident ":" type ["=" expr]
```

Classes define object templates with fields and methods. Views, fields, and methods can appear in any order. Jo provides two mutually exclusive syntaxes for defining constructors.

**Option 1: Class parameters**

Declare parameters directly after the class name. The compiler generates a constructor automatically:

```jo
class Point(x: Int, y: Int)
  val cachedHash: Int = x * 31 + y

  def toString(): String = "Point(" + x + ", " + y + ")"
end

val p = new Point(3, 4)
```

The class parameters (`x`, `y`) become immutable fields. Fields with initializers have their RHS evaluated during construction.

A class with class parameters or with an empty body is considered a **data class**. For data classes, the compiler automatically generates constructor functions and pattern definitions for pattern matching.

The `Point` class above desugars to:

```jo
class Point
  val x: Int
  val y: Int
  val cachedHash: Int

  def Point(x: Int, y: Int): Point =
    this.x = x
    this.y = y
    this.cachedHash = x * 31 + y
    this
end

// Automatically generated constructor function
def Point(x: Int, y: Int): Point = new Point(x, y)

// Automatically generated pattern for pattern matching
pattern Point(x: Int, y: Int): Point =
  case p then x = p.x, y = p.y
```

**Option 2: Explicit constructor**

Define a constructor method with the class name for custom initialization logic:

```jo
class Rectangle
  val w: Int
  val h: Int
  var area: Int

  def Rectangle(width: Int, height: Int): Rectangle =
    this.w = width
    this.h = height
    this.area = width * height
    this
end
```

!!! warning "Mutually Exclusive Syntaxes"
    Class parameters and explicit constructors cannot coexist. Use class parameters for convenience, or write an explicit constructor for custom initialization logic.

## Initialization

Constructor requirements:

- Return type must be the class type if declared
- Body contains field initialization assignments (`this.field = expr`)
- All fields must be initialized
- Field assignments can appear anywhere in the body, with code before and between them
- RHS of field assignments is type-checked without `this` in scope (only parameters available)
- `this` becomes available once all fields are initialized
- Constructor automatically appends `this` to return the instance

**Example with code before and between initializations:**

```jo
class Circle
  val radius: Int
  val area: Int
  var scaleFactor: Int

  def Circle(r: Int, scale: Int): Circle =
    // Code before initialization (this not available)
    val adjustedRadius = if r < 1 then 1 else r

    // First initialization
    this.radius = adjustedRadius

    // Code between initializations (this not available)
    val pi = 3  // Simplified pi
    val computedArea = pi * adjustedRadius * adjustedRadius

    // More initializations
    this.area = computedArea
    this.scaleFactor = scale
    // All fields now initialized - this becomes available!

    // Code after all fields initialized (this available!)
    this.normalize()  // Can call methods on this
  end

  def normalize(): Unit =
    if scaleFactor < 1 then
      scaleFactor = 1
    end
  end
end
```

**Fields with initializers:**

Both approaches support fields with initializers:

```jo
class Counter(initial: Int)
  val count: Int = initial    // RHS can reference constructor parameters
  var total: Int = 0          // RHS is a constant
end
```

Field initializers are evaluated during construction without `this` in scope. However, previously initialized fields are available, allowing fields to reference earlier fields in their initialization expressions.

**Initialization order:**

1. Statements execute in order
2. Field initializers evaluated when their field is assigned
    - Each field becomes available in scope after initialization
    - Later field initializers can reference earlier initialized fields (without `this` prefix)
3. Once all fields are initialized, `this` becomes available
4. Remaining statements can use `this`
5. Instance returned (automatic `this` append)

**Example: Fields referencing earlier fields**

```jo
class Rectangle(width: Int, height: Int)
  val area: Int = width * height        // Can reference constructor parameters
  val perimeter: Int = 2 * (width + height)  // Can reference constructor parameters
  val isSquare: Bool = width == height

  // Can reference earlier initialized fields
  val description: String =
    if isSquare then "Square with area " + area
    else "Rectangle with area " + area
end
```

In this example, the fields are initialized in declaration order:

1. `area` uses constructor parameters `width` and `height`
2. `perimeter` uses constructor parameters `width` and `height`
3. `isSquare` uses constructor parameters `width` and `height`
4. `description` uses the previously initialized field `isSquare` and `area`

**Immutability:**

Constructor parameters and `val` fields are immutable. Use `var` for mutable fields:

```jo
class Account(id: Int)
  var balance: Int = 0

  def deposit(amount: Int): Unit =
    this.balance = this.balance + amount
end
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

## See Also

- [Class Types](../types/class-types.md) - Class type system and subtyping rules
- [Interface Definitions](interface-definitions.md) - Implementing interfaces
- [Algebraic Data Types](adt.md) - Union type definitions
