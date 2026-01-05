# Class Types

Class types define structured data with constructors and methods.

## Simple Classes

Classes with constructor parameters:

```jo
class Config(host: String, port: Int, timeout: Int)

val config = Config("localhost", 8080, 30)
```

## Classes with Methods

Classes can have methods that operate on their data:

```jo
class Rectangle(width: Int, height: Int)
  def area: Int = width * height
  def perimeter: Int = 2 * (width + height)
end

val rect = Rectangle(5, 10)
val rectArea = rect.area  // 50
val rectPerimeter = rect.perimeter  // 30
```

## Classes with Mutable Fields

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

## Explicit Constructors

Classes can define explicit constructors using a method named after the class:

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

## Generic Classes

Classes can be parameterized with type parameters:

```jo
class Box[T](value: T)
  def map[U](f: T => U): Box[U] = Box(f(value))
  def get: T = value
end

val intBox = Box(42)
val stringBox = intBox.map(x => x.toString)
```

## Data Classes

Classes with constructor parameters or empty bodies are **data classes**. The compiler automatically generates:
- Constructor functions
- Pattern definitions for pattern matching

```jo
class Point(x: Int, y: Int)

// Automatically generated constructor
val p = Point(10, 20)

// Automatically generated pattern for matching
match p
case Point(x, y) => println ("x=" + x + ", y=" + y)
end
```

## Class vs Interface

Classes define concrete types with implementation, while interfaces define behavioral contracts. Classes can implement interfaces through views:

```jo
interface Drawable
  def draw(): Unit receives IO.stdout
end

class Circle(radius: Int)
  def area: Float = 3.14159 * radius * radius
end

// Adapter function to convert Circle to Drawable
def circleToDrawable(circle: Circle): Drawable = new Drawable
  def draw(): Unit receives IO.stdout =
    println("Circle with radius " + circle.radius)
  end
end

// View type to make Circle implement Drawable
type DrawableCircle = view Circle as Drawable with circleToDrawable
```

## See Also

- [Interface Definitions](../definitions/interface-definitions.md) - For behavioral contracts
- [View Types](view-types.md) - For extending classes with interfaces
- [Class Definitions](../definitions/class-definitions.md) - For detailed syntax
