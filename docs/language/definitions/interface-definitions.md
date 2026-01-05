# Interface Definitions

Interfaces define behavioral contracts with method declarations. Classes implement interfaces through views, enabling flexible type extension without modifying original definitions.

## Syntax

```jo
interface InterfaceName
  method declarations
end

interface InterfaceName[T]
  generic method declarations
end
```

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
class Point(x: Int, y: Int)

interface Drawable
  def draw(): Unit receives IO.stdout
end

// Define adapter
def pointToDrawable(p: Point): Drawable = new Drawable
  def draw(): Unit receives IO.stdout =
    println("Point(" + p.x + ", " + p.y + ")")
end

// Create view type
type DrawablePoint = view Point as Drawable with pointToDrawable

// Use view
def render(p: DrawablePoint): Unit =
  p.draw()  // From Drawable
  // Also has Point methods: p.x, p.y
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

## Multiple Interface Implementation

Views can implement multiple interfaces:

```jo
interface Printable
  def print(): Unit receives IO.stdout
end

interface Serializable
  def serialize(): String
end

class User(name: String, age: Int)

type RichUser = view User as
  Printable with userToPrintable,
  Serializable with userToSerializable
```

## Examples

### Logging Interface

```jo
interface Logger
  def debug(message: String): Unit receives IO.stdout
  def info(message: String): Unit receives IO.stdout
  def warn(message: String): Unit receives IO.stdout
  def error(message: String): Unit receives IO.stdout
end

// Implementation
class ConsoleLogger
  def debug(message: String): Unit receives IO.stdout =
    println("[DEBUG] " + message)

  def info(message: String): Unit receives IO.stdout =
    println("[INFO] " + message)

  def warn(message: String): Unit receives IO.stdout =
    println("[WARN] " + message)

  def error(message: String): Unit receives IO.stdout =
    println("[ERROR] " + message)
end
```

### Generic Container

```jo
interface Container[T]
  def add(item: T): Unit
  def remove(item: T): Bool
  def contains(item: T): Bool
  def size(): Int
  def isEmpty(): Bool = size() == 0  // Default implementation
end

class ListContainer[T]
  var items: List[T]

  def ListContainer() =
    this.items = []

  def add(item: T): Unit =
    this.items = [item, ..this.items]

  def remove(item: T): Bool =
    // Implementation
    ...

  def contains(item: T): Bool =
    // Implementation
    ...

  def size(): Int =
    this.items.length
end
```

### Repository Pattern

```jo
interface Repository[T]
  def findById(id: Int): Option[T] receives database
  def findAll(): List[T] receives database
  def save(item: T): Unit receives database
  def delete(id: Int): Bool receives database
end

class UserRepository
  def findById(id: Int): Option[User] receives database =
    database.query("SELECT * FROM users WHERE id = " + id)

  def findAll(): List[User] receives database =
    database.query("SELECT * FROM users")

  def save(user: User): Unit receives database =
    database.execute("INSERT INTO users ...")

  def delete(id: Int): Bool receives database =
    database.execute("DELETE FROM users WHERE id = " + id)
end
```

## Interface Composition

Interfaces can reference other interfaces:

```jo
interface Readable
  def read(): String receives IO
end

interface Writable
  def write(content: String): Unit receives IO
end

interface ReadWritable
  // Combine behaviors
  def read(): String receives IO
  def write(content: String): Unit receives IO
end
```

## Best Practices

### Small Focused Interfaces

```jo
// ✓ Good - focused interface
interface Closeable
  def close(): Unit
end

interface Readable
  def read(): String receives IO
end

// ⚠ Too broad
interface FileOperations
  def read(): String receives IO
  def write(content: String): Unit receives IO
  def delete(): Unit receives IO
  def rename(newName: String): Unit receives IO
  def copy(dest: String): Unit receives IO
end
```

### Meaningful Names

```jo
// ✓ Good
interface Iterator[T]
interface Comparable[T]
interface Serializable

// ⚠ Less clear
interface I1
interface Handler  // Too vague
```

### Document Contracts

```jo
interface Validator[T]
  // Must return true if item is valid, false otherwise
  // Should not throw exceptions
  def validate(item: T): Bool
end
```

## See Also

- [View Types](../types/view-types.md) - Implementing interfaces
- [Lambda Types](../types/lambda-types.md) - Lambda interface adaptation
- [Class Definitions](class-definitions.md) - Implementing interfaces in classes
- For detailed design, see [Interfaces and Views](../../design/interface-views.md)
