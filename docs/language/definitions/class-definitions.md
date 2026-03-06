# Class Definitions

Classes define new types with fields and methods. Jo supports data classes with automatic constructors and full classes with explicit constructors and mutable state.

## Syntax

```
class_def = "class" ident [type_params] [params] {class_member} ["end"]
class_member = view_decl | extension_ref | field | method
field = ("val" | "var") ident ":" type ["=" expr]
extension_ref = "extension" qualid
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

A class with class parameters but without fields in class body is considered a **data class**. For data classes, the compiler automatically generates constructor functions and pattern definitions for pattern matching.

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
- `this` is available throughout the constructor body
- Constructor automatically appends `this` to return the instance

**Example with code before and between initializations:**

```jo
class Circle
  val radius: Int
  val area: Int
  var scaleFactor: Int

  def Circle(r: Int, scale: Int): Circle =
    // Code before initialization (`this` is already available)
    val adjustedRadius = if r < 1 then 1 else r

    // First initialization
    this.radius = adjustedRadius

    // Code between initializations
    val pi = 3  // Simplified pi
    val computedArea = pi * adjustedRadius * adjustedRadius

    // More initializations
    this.area = computedArea
    this.scaleFactor = scale
    // All fields now initialized

    // Code after all fields initialized
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

1. Statements execute in order
2. Field initializers evaluated when their field is assigned
3. Instance returned (automatic `this` append)

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

!!! warning "Object Initialization Safety"
    It is not recommended to perform complex side effects in constructors or leak `this` before the object is fully initialized.
    Such patterns can observe partially initialized state and are easy to get wrong.
    In the future, Jo may add an initialization checker inspired by Fengyun Liu et al.,
    "Safe Object Initialization, Abstractly" (SCALA '21):
    <https://dl.acm.org/doi/abs/10.1145/3486610.3486895>

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

## Views

Classes can declare views to implement interfaces or delegate to other objects. For high-level concepts, see [Classes and Views](../concepts/interface-views.md). This section covers the technical details.

### View Declaration Syntax

**Direct views:**

```jo
class_member = "view" type_ref

class ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger  // Direct view: creates subtyping
end
```

**Delegate views:**

```jo
class_member = "view" type_ref "=" expr

class Service(logger: Logger)
  view Logger = logger  // Delegate view: creates view field
end
```

### View Conformance Checking

When a class declares `view I[T1, ..., Tn]` (direct view), the compiler verifies:

#### 1. Interface Resolution

`I` must resolve to an interface definition (not a class or type alias).

#### 2. Type Parameter Arity

The number of type arguments must match the interface's type parameters.

#### 3. Method Implementation Requirements

For each method `m` in interface `I`:

- **If `m` is abstract** (no body): Class must have a member `m` with compatible signature
- **If `m` is concrete** (has body):
    - Class must NOT have a method with the same name
    - Class must NOT have a field with the same name

**Signature compatibility includes:**

- Parameter count and types (after type parameter substitution)
- Return type (after type parameter substitution)
- Effect requirements

**Example:**

```jo
interface Iterator[T]
  def hasNext(): Bool  // Abstract - must implement
  def next(): T        // Abstract - must implement
  def forEach(f: T -> Unit): Unit =  // Concrete - must NOT override
    while hasNext() do
      f(next())
end

class Range(start: Int, end: Int)
  var current: Int = start

  // Must implement abstract methods
  def hasNext(): Bool = current < end
  def next(): Int =
    val value = current
    current = current + 1
    value

  view Iterator[Int]
  // Inherits concrete forEach - cannot override
end
```

#### View Type Requirements

View types must be **interface or class types**, not type aliases:

```jo
interface Foo
  def hello(): String
end

type FooAlias = Foo

class Bar
  def hello(): String = "hello"
  view FooAlias  // Error: view type must be interface or class, not type alias
end

class Baz(foo: Foo)
  view FooAlias = foo  // Error: view type must be interface or class, not type alias
end
```

**Rationale: Coherence in Type Adaptation**

This restriction ensures that **a class cannot have two views of the same underlying type**. Consider what would happen if type aliases were allowed:

```jo
interface Logger
  def log(msg: String): Unit
end

type LoggerAlias = Logger
type AnotherLoggerAlias = Logger

class Service(logger1: Logger, logger2: Logger)
  view Logger = logger1
  view LoggerAlias = logger2        // Would be duplicate of Logger!
  view AnotherLoggerAlias = logger1 // Would be duplicate of Logger!
end
```

All three view declarations refer to the same underlying type (`Logger`), but have different names. This creates ambiguity:

- Which view should be used for type adaptation from `Service` to `Logger`?
- Which field should member selection use when resolving `service.log("hello")`?

By requiring views to be **nominal types** (interfaces or classes), we ensure:

1. **Unique view identification**: Each view is identified by its interface/class name
2. **Deterministic adaptation**: Type adaptation from class type to interface/class type has exactly one possible view (or none)
3. **Clear member resolution**: Member selection through views is unambiguous

This is critical for **coherence in type adaptation**: given a class type and a target interface/class type, there is at most one applicable view, making the adaptation deterministic and predictable.

### View Field Semantics

Each `view T = expr` declaration creates an immutable field `val T: T` that holds the result of evaluating `expr`.

```jo
class Service(logger: Logger)
  view Logger = logger  // Creates: val Logger: Logger
end

// Equivalent to:
class Service(logger: Logger)
  val Logger: Logger = logger
end
```

**Key properties:**

1. **View fields**: Delegate views (`view T = expr`) create an immutable field `val T: T`. Direct views (`view I`) do NOT create fields—they only establish subtyping.
2. **Single instance**: The same view instance is returned on every access
3. **Evaluation**: For `view T = expr`, expression is evaluated once at construction time
4. **Immutability**: View fields are always immutable (`val`)

**Immutability consequence:**

```jo
class Service(var logger: Logger)
  view Logger = logger  // Immutable delegation field

  def swapLogger(newLogger: Logger): Unit =
    logger = newLogger  // Constructor param is mutable
    // But delegation field remains unchanged!
    // Delegated calls still use original logger
end
```

If dynamic delegation is needed, implement methods explicitly instead of using `view I = expr`.

**Valid delegate views:**

```jo
interface Logger
  def log(msg: String): Unit
end

class Employee
  def work(): Unit = ...
end

// Case 1: Delegate to interface type
class Service1(logger: Logger)
  view Logger = logger  // OK: logger has type Logger
end

// Case 2: Delegate to class type
class Person(emp: Employee)
  view Employee = emp  // OK: emp has type Employee
end

// Case 3: Delegate via accessor
class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger
end

class Service2(logger: FileLogger)
  view Logger = logger.Logger  // OK: access Logger view from FileLogger
end
```

**Invalid delegate view (type mismatch):**

```jo
class Service3(logger: FileLogger)
  view Logger = logger  // Error: FileLogger != Logger (nominal typing)
end
```

### View Consistency

A class must not declare the same view twice (among all direct and delegate views):

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger1: Logger, logger2: Logger)
  view Logger = logger1
  view Logger = logger2  // Error: Duplicate view declaration for Logger
end
```

This ensures **coherence in type adaptation**: given a class type and a target interface/class type, there is at most one applicable view, making adaptation deterministic and predictable.

### Member Selection with Views

For `expr.member` where `expr: C`, member selection algorithm:

1. **Direct member lookup**: Search for `member` in type `C`. If found, use it.
2. **View member lookup (non-recursive)**: If not found, search all views (direct and delegate).
     - For each `view T` declared by `C`, check if `T` has `member`
     - If exactly one view provides `member`, resolve to `expr.T.member`
     - If multiple views provide `member`, report ambiguity error

!!! warning "Non-Recursive Member Lookup"
    Member selection only searches views **directly declared** by the class. It does NOT recursively search through views of delegated objects.

    ```jo
    class FileLogger(path: String)
      def log(msg: String): Unit = ...
      view Logger  // FileLogger declares Logger view
    end

    class Service(logger: FileLogger)
      view FileLogger = logger  // Service gets FileLogger view only
      // Service does NOT get Logger view transitively!
      // To expose Logger, you must declare: view Logger = logger
    end
    ```

### Implicit View Adaptation

During type adaptation from `expr: C` to `expected: T`:

1. **Direct match**: If `C <: T`, succeed
     - This includes direct views: if `C` declares `view I`, then `C <: I`

2. **Delegate view search (non-recursive)**: If `C` directly declares `view T = expr` (delegate view), compiler automatically adapts through the delegate view

**Example:**

```jo
class Service(logger: Logger)
  view Logger = logger  // Delegate view (no subtyping)
end

def useLogger(l: Logger): Unit = l.log("msg")

val service = new Service(someLogger)
useLogger(service)  // Implicit adaptation through Logger view
```

Type annotation also triggers implicit view adaptation:

```jo
val range = new Range(0, 10)
val iter = range.iterator()
val iterView: Iterator[Int] = iter  // Implicit view adaptation
```

!!! info "View search is **non-recursive** and **exact**"

    To trigger implicit delegate view selection, only views directly declared in the class are checked. Users must make indirect views available for adaptation explicitly with an additional view declaration.

    In addition, the target type must exactly match the view type. Subtyping can leak the nested interfaces of the delegate views and misinterpret user's intent if the target type is `C | D`.

    **Rationale**: View adaptation is too powerful a mechanism. Users need to make their intent clear.

## Extensions

Classes can reference externally defined extensions to add methods to the class type:

```jo
class Complex(re: Float, im: Float)
  extension ComplexArith
  extension ComplexShow
end
```

The referenced extension is defined separately:

```jo
extension ComplexShow(it: Complex)
  def toString: String =
    it.re.toString + " + " + it.im.toString + "i"
end
```

For full syntax and typing rules, see [Extension Definitions](extension-definitions.md).

### Generic Class Example

```jo
class Box[T](value: T)
  extension BoxOps
  def get: T = value
end

extension BoxOps[T](it: Box[T])
  def duplicate: Pair[T, T] = Pair(it.get, it.get)
end

val b = Box(42)
val p = b.duplicate
```

Type arguments are inferred from the class type when attaching generic extensions.
Type arguments are not written at the attachment site (`extension BoxOps`, not `extension BoxOps[Int]`).

### Extension Semantics in Classes

1. **External method model**: Extension methods are external functions. They follow normal visibility rules and have no special private access.
2. **Explicit view conformance**: Extension methods do **not** satisfy abstract methods required by class views. Abstract requirements are satisfied only by class methods.
3. **Generic compatibility**: For generic extensions, methods are instantiated as needed. After instantiation, the extension method pre-parameter type must be a supertype of the base class type.
4. **Class type includes extension methods**: When a class references an extension, those extension methods are available on values of that class type.

!!! note "Why extension methods do not fulfill view requirements"
    View conformance in Jo is intentionally explicit: abstract `view` requirements are checked against class methods only.
    This keeps interface implementation local to the class declaration and preserves semantic lucidity.
    A class method can still delegate to any implementation strategy (including top-level functions or extensions), but the conformance boundary stays explicit in the class body.

### Name Conflict Example

```jo
class Complex(re: Float, im: Float)
  extension ComplexShow
  def toString: String = "Complex(" + re.toString + ", " + im.toString + ")"
  // Error if ComplexShow also defines toString
end
```

## Member Consistency

Member consistency applies to classes that use direct members, views, and extensions. All visible members form a **single unified namespace** and member names must be unique across:

1. **Direct members** in the class or object (methods and fields)
2. **Concrete methods** inherited from direct views
3. **Visible members** from delegate views
4. **Methods** from referenced extensions

This avoids ambiguity in member selection and helps maintain a simple and coherent mental model for classes.

```jo
interface Logger
  def log(msg: String): Unit
end

extension LoggingOps(it: Service)
  def log(msg: String): Unit = println(msg)
end

class Service
  def log(msg: String): Unit = println("service: " + msg)
  extension LoggingOps  // Error: duplicate member name log
end
```

!!! warning "Unique Member Names in Unified Namespace"
    Member names must be globally unique within the effective API surface of a class.

    A conflict is reported when the same member name can be reached from multiple sources, including:

    - direct class/object members
    - concrete methods from direct views
    - visible members from delegate views
    - methods imported through `extension` references

    The essential rule is member-selection coherence: `x.m` should resolve to exactly one target, without requiring users to reason about lookup priority between class/view/extension sources.

## See Also

- [Class Types](../types/class-types.md) - Class type system and subtyping rules
- [Interface Definitions](interface-definitions.md) - Implementing interfaces
- [Algebraic Data Types](adt.md) - Union type definitions
- [Classes and Views](../concepts/interface-views.md) - High-level design and philosophy
