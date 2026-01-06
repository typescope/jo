# Interface Definitions

Interfaces define behavioral contracts with method declarations. Classes implement interfaces through views.

## Basic Interfaces

```jo
// Simple interface
interface Logger
  def info(message: String): Unit
  def error(message: String): Unit
end

// Generic interface
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end
```

## Method Declarations

Interfaces declare method signatures:

```jo
interface Comparable[T]
  def compare(x: T, y: T): Int
  def lessThan(x: T, y: T): Bool
  def greaterThan(x: T, y: T): Bool
end

interface Repository[T]
  def find(id: Int): Option[T]
  def save(item: T): Unit
  def delete(id: Int): Unit
end
```

## Concrete Methods

Interfaces can provide default implementations:

```jo
interface Comparable[T]
  def compare(x: T, y: T): Int  // Abstract

  // Concrete defaults
  def equals(x: T, y: T): Bool = compare(x, y) == 0
  def lessThan(x: T, y: T): Bool = compare(x, y) < 0
  def greaterThan(x: T, y: T): Bool = compare(x, y) > 0
end
```

## Interfaces with Effects

Methods can declare effect requirements:

```jo
interface FileSystem
  def readFile(path: String): String receives open
  def writeFile(path: String, content: String): Unit receives open
  def deleteFile(path: String): Unit receives open
end

interface Logger
  def log(level: String, message: String): Unit receives IO.stdout
end
```

## Generic Interfaces

```jo
interface Container[T]
  def add(item: T): Unit
  def remove(item: T): Bool
  def contains(item: T): Bool
  def size(): Int
end

interface Functor[F[_], A]
  def map[B](f: A => B): F[B]
end
```

## Implementing Interfaces Through Views

Classes implement interfaces using the view mechanism:

```jo
interface Drawable
  def draw(): Unit receives IO.stdout
end

class Point(x: Int, y: Int)
  def draw(): Unit receives IO.stdout =
    println("Point(" + x + ", " + y + ")")

  view Drawable
end

// Use view
val p = Point(10, 20)
p.draw()
```

## Lambda Interface Adaptation

Lambdas automatically adapt to single-method interfaces:

```jo
interface Predicate[T]
  def test(x: T): Bool
end

// Lambda adapts to interface
val isEven: Predicate[Int] = x => x % 2 == 0

// Use as interface
if isEven.test(4) then
  println("4 is even")
```

Context parameters in lambda interfaces come from the call site:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)

// Context parameter provided at call site
logger.log("test") with IO.stdout = customOutput
```

## See Also

- [Classes, Interfaces and Views](../concepts/interface-views.md) - High-level design
- [View Types](../types/view-types.md) - Implementing interfaces
- [Lambda Types](../types/lambda-types.md) - Lambda interface adaptation
- [Class Definitions](class-definitions.md) - Implementing interfaces in classes
