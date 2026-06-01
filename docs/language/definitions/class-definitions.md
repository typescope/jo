# Class Definitions

Classes define new types with fields and methods. Jo supports data classes with automatic constructors and full classes with explicit constructors and mutable state.

## Syntax

```
class_def    = "class" ident [type_params] [params] {class_member} ["end"]
class_member = view_decl | field | method
field        = ("val" | "var") ident ":" type ["=" expr]
view_decl    = "view" type_ref ["=" ref]
```

Views, fields, and methods can appear in any order inside a class body.

## Constructor

Jo provides two mutually exclusive syntaxes for defining constructors.

### Class Parameters

Declare parameters directly after the class name. The compiler generates a constructor automatically:

```jo
class Point(x: Int, y: Int)
  val cachedHash: Int = x * 31 + y

  def toString(): String = "Point(" + x + ", " + y + ")"
end

val p = new Point(3, 4)
```

The class parameters (`x`, `y`) become immutable fields. Fields with initializers have their RHS evaluated during construction.

A class with class parameters but without fields in the class body is considered a **data class**. For data classes, the compiler automatically generates a constructor function and a pattern definition for pattern matching.

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

### Explicit Constructor

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

::: warning Mutually Exclusive Syntaxes
Class parameters and explicit constructors cannot coexist. Use class parameters for convenience, or write an explicit constructor for custom initialization logic.
:::

## Fields

Fields declared with `val` are immutable; fields declared with `var` are mutable and
can be reassigned after construction:

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

## Methods

Methods are defined with `def`. Method names can be regular identifiers or operators,
enabling natural infix and prefix syntax. Prefix operators are distinguished by a leading
`~`:

```jo
class Vec2(x: Float, y: Float)
  def +(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)  // infix: a + b
  def ~-(): Vec2 = Vec2(-x, -y)                               // prefix: -a
  def dot(other: Vec2): Float = x * other.x + y * other.y
end
```

Methods may also declare context parameter requirements using `receives`:

```jo
class Logger
  def log(message: String): Unit receives IO.stdout =
    val timestamp = getCurrentTime()
    val entry = timestamp + ": " + message + "\n"
    println entry
end
```

## Type Parameters

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

## Views

Classes declare views to fulfill interface contracts. For the high-level design
philosophy, see [Classes and Views](../concepts/interface-views.md). This section
covers the technical details.

### View Declaration Syntax

**Direct view** — the class implements the interface itself:

```jo
class ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger
end
```

**Delegate view** — the class delegates to a held object:

```jo
class Service(logger: Logger)
  view Logger = logger
end
```

Both forms create a subtype relationship `C <: I`. The delegate view requires `I` to be
an interface type and `ref` to be a **stable reference** (an immutable field or a chain
of immutable field selections) whose type conforms to `I`. The compiler synthesizes a
forwarding method for each abstract method of `I`, delegating to `ref`.

### View Conformance Checking

When a class declares `view I[T1, ..., Tn]`, the compiler verifies:

#### 1. Interface Resolution

`I` must resolve to an interface definition with the correct number of type arguments.
Classes and type aliases are not permitted as view types.

#### 2. Method Requirements

For each method `m` in interface `I`:

- **Abstract** (`m` has no body): the class must provide an implementation of `m` with a
  compatible signature. For a direct view, `m` must be defined in the class body. For a
  delegate view, the synthesized forwarder satisfies this requirement automatically.
- **Concrete** (`m` has a body): the class must NOT define a method or field with the
  same name. Concrete interface methods are final and inherited as-is.

**Signature compatibility includes:**

- Parameter count and types (after type parameter substitution)
- Return type (after type parameter substitution)
- Context parameter requirements

#### 3. Delegate View Requirements

For `view I = ref`, the compiler additionally checks:

- `ref` is a stable reference: `this`, or an immutable field selection on a stable reference
- `ref` conforms to `I` nominally (`ref.tpe <: I`)

```jo
class Service(logger: Logger)
  view Logger = logger      // OK: logger is immutable and Logger <: Logger

class Service(var logger: Logger)
  view Logger = logger      // Error: logger is mutable

class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger

class Service(fl: FileLogger)
  view Logger = fl          // OK: fl is immutable and FileLogger <: Logger
  view Logger = fl.friend   // OK if friend is an immutable field of type Logger
```

### View Consistency

A class cannot declare two views of the same interface — doing so would make it
ambiguous which implementation is used. The compiler rejects duplicate view declarations
regardless of whether they are direct or delegate:

```jo
class Service(logger: Logger)
  view Logger        // Error: duplicate view for Logger
  view Logger = logger
end
```

### Member Consistency

All members visible through a class form a **single unified namespace**. Member names
must be unique across:

1. Direct members (methods and fields defined in the class body)
2. Synthetic forwarders generated from delegate views
3. Concrete methods inherited from direct views

This prevents ambiguity and ensures every member name has a single unambiguous meaning.

```jo
interface Logger
  def log(msg: String): Unit
end

interface Auditor
  def log(event: String): Unit  // same name as Logger.log
end

class Service(logger: Logger, auditor: Auditor)
  view Logger = logger
  view Auditor = auditor  // Error: 'log' conflicts between Logger and Auditor forwarders
end
```

## Initialization

Constructor requirements:

- Return type must be the class type if declared
- Body contains field initialization assignments (`this.field = expr`)
- All fields must be initialized
- Field assignments can appear anywhere in the body, with code before and between them
- `this` is available throughout the constructor body
- Constructor automatically appends `this` to return the instance

**Example with code before and between initializations:**

```jo
class Circle
  val radius: Int
  val area: Int
  var scaleFactor: Int

  def Circle(r: Int, scale: Int): Circle =
    val adjustedRadius = if r < 1 then 1 else r

    this.radius = adjustedRadius

    val pi = 3  // Simplified pi
    val computedArea = pi * adjustedRadius * adjustedRadius

    this.area = computedArea
    this.scaleFactor = scale

    this.normalize()
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

Field initializers are evaluated during construction.

**Initialization order:**

Fields are initialized in declaration order. Each field may reference constructor
parameters and any previously initialized fields.

```jo
class Rectangle(width: Int, height: Int)
  val area: Int = width * height
  val perimeter: Int = 2 * (width + height)
  val isSquare: Bool = width == height

  val description: String =
    if isSquare then "Square with area " + area
    else "Rectangle with area " + area
end
```

::: warning Object Initialization Safety
It is not recommended to perform complex side effects in constructors or leak `this` before the object is fully initialized.
Such patterns can observe partially initialized state and are easy to get wrong.
In the future, Jo may add an initialization checker inspired by Fengyun Liu et al.,
"Safe Object Initialization, Abstractly" (SCALA '21):
<https://dl.acm.org/doi/abs/10.1145/3486610.3486895>
:::

## See Also

- [Class Types](../types/class-types.md) - Class type system and subtyping rules
- [Interface Definitions](interface-definitions.md) - Interface method requirements
- [Algebraic Data Types](adt.md) - Union type definitions
- [Classes and Views](../concepts/interface-views.md) - High-level design and philosophy
