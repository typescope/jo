# Interfaces and Views

## Overview

Interfaces define behavioral contracts independently from implementation. Views enable classes to expose multiple interfaces without creating subtype hierarchies. This design supports composition over inheritance while maintaining static type safety.

## Motivation

Views represent a fundamental design philosophy about how we conceptualize types and behavior in programming languages.

Traditional object-oriented languages use **subtyping hierarchies** to model relationships: if class `C` implements interface `I`, then `C` is a subtype of `I` (`C <: I`). This approach reflects a taxonomic worldview—classifying entities into fixed categories with inheritance relationships. While intuitive, it creates rigid hierarchies that couple concrete types to abstract contracts, leading to fragile base classes, forced taxonomies, and the infamous diamond problem.

Dynamic languages like Python and JavaScript embrace **duck typing**: "if it walks like a duck and quacks like a duck, it's a duck." Objects are used based on their available methods, without explicit type declarations. This provides flexibility and composition freedom, but sacrifices static type safety.

Jo's **view mechanism** bridges these paradigms. Views bring the compositional benefits of duck typing into a statically typed setting:

- **Type-safe flexibility**: A class can expose multiple interfaces without rigid hierarchies
- **Composition over classification**: Types are composed of capabilities (views), not organized into taxonomies
- **Explicit relationships**: View conformance is declared but does not create subtype relationships
- **Behavioral contracts**: Interfaces define what can be done, independent of what things are

This reflects a different philosophical stance: rather than asking "what is this thing?" (subtyping), we ask "what can we do with this thing?" (views). The same object can be viewed through different lenses depending on context, without being forced into a single taxonomic hierarchy.

Views provide automatic delegation (`view I = expr`), type-directed selection, and member resolution through interfaces—bringing duck typing's flexibility to a statically typed language while maintaining safety and explicit design.

## Syntax

### Interface Definition

```
interface_def = "interface" ident type_params method_decl*
method_decl = "def" ident params ":" type ["=" expr]
```

Interfaces define pure behavioral contracts:

```jo
interface Serializer[T]
  def encode(x: T): String
  def decode(s: String): T
end

interface Show
  def show(): String
end

interface Group[T]
  def combine(x: T, y: T): T
  def identity: T
end

interface Comparable[T]
  def compare(x: T, y: T): Int
  def equals(x: T, y: T): Bool = compare(x, y) == 0  // Default implementation
end
```

### View Declaration in Classes

```
view_decl = "view" type_tree ["=" expr]
```

Classes declare views using the `view` keyword. With Jo's simplified class syntax, constructor parameters are declared directly:

**Manual implementation:**

```jo
class Point(x: Int, y: Int)
  // Members implementing Show view
  def show(): String = "Point(" + intToStr(x) + ", " + intToStr(y) + ")"

  // Members implementing Comparable[Point] view
  def compare(p1: Point, p2: Point): Int =
    val dx = p1.x - p2.x
    if dx != 0 then dx else p1.y - p2.y

  // Declare views
  view Show
  view Comparable[Point]
end
```

**Multiple manual views:**

```jo
class User(id: Int, name: String)
  def show(): String = "User(" + intToStr(id) + ", " + name + ")"
  def encode(u: User): String = intToStr(u.id) + ":" + u.name
  def decode(s: String): User = ...

  view Show
  view Serializer[User]
end
```

**View delegation:**

Views can delegate to concrete instances using `view I = expr`:

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

class Service(logger: Logger, config: Config)
  view Logger = logger  // Delegates to logger instance

  def process(): Unit =
    log("Starting process")  // Delegates via member selection
end
```

See the Semantics section for desugaring and complete delegation semantics.

### Explicit View Selection

The `view ... as ...` syntax explicitly selects a view from a value:

```jo
val user = new User(1, "Alice")
val showable: Show = view user as Show
val serialized = showable.show()
```

Type annotation can trigger implicit view selection:

```jo
val user = new User(1, "Alice")
val showable: Show = user  // Implicit view selection
```

## Semantics

### Interface Types

An interface type `I[T1, ..., Tn]` represents any value that conforms to the interface contract. Interface types are:

1. **First-class types**: Can be used as parameter types, return types, field types
2. **Non-subtype-related to classes**: A class type `C` is NOT a subtype of its view `I`, even if `C` declares `view I`
3. **Structurally defined**: Interfaces define required methods and their signatures

### View Declaration and Conformance

A class declares view conformance using `view I[T1, ..., Tn]`. The type checker verifies:

1. **Method presence**: All required methods of the interface exist in the class
2. **Signature compatibility**: Method signatures match the interface contract, including:
    - Parameter types
    - Return type
    - Auto parameters
    - Effect requirements (class methods must have equal or fewer effects)
3. **Accessibility**: Methods are accessible (not private)

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

class ConsoleLogger
  def log(msg: String): Unit receives IO.stdout = println(msg)
  def error(msg: String): Unit receives IO.stderr = eprintln(msg)

  view Logger  // Valid: both methods present with compatible signatures
end

class PartialLogger
  def log(msg: String): Unit receives IO.stdout = println(msg)

  view Logger  // Error: missing method 'error'
end
```

!!! info "No Subtyping Relationship"
    A class type is NOT a subtype of its views. View selection is either explicit (via `view ... as ...`) or via type-directed adaptation. There is no upcasting or downcasting.

### Default Method Implementation

Interfaces can provide default implementations:

```jo
interface Eq[T]
  def equals(x: T, y: T): Bool
  def notEquals(x: T, y: T): Bool = !equals(x, y)  // Default implementation
end

class Counter(count: Int)
  // Only need to implement equals, notEquals gets default
  def equals(c1: Counter, c2: Counter): Bool = c1.count == c2.count

  view Eq[Counter]
end
```

Classes can override default implementations:

```jo
class FastCounter(count: Int)
  def equals(c1: FastCounter, c2: FastCounter): Bool = c1.count == c2.count

  // Override default with optimized version
  def notEquals(c1: FastCounter, c2: FastCounter): Bool = c1.count != c2.count

  view Eq[FastCounter]
end
```

### Type Parameter Variance

Interface type parameters are **invariant**:

```jo
interface Container[T]
  def get(): T
  def set(v: T): Unit

// Container[Int] is NOT a subtype of Container[Any]
// Container[Any] is NOT a subtype of Container[Int]
// Each instantiation is a distinct type
```

!!! info

    Since Jo has no subtyping between classes and interfaces, variance annotations would add complexity without benefit. Invariance is simpler and safer.

### View Delegation Semantics

View delegation `view I = expr` desugars to a view declaration plus an immutable field:

```jo
class Service(logger: Logger)
  view Logger = logger
end

// Desugars to:
class Service(logger: Logger)
  view Logger              // Declare the view
  val Logger: Logger = logger  // Store immutable field
end
```

**Key properties:**

1. **No synthesized methods**: Delegation happens via member selection at usage sites
2. **Immutable binding**: Field is immutable (`val`), binds at construction time
3. **Nominal typing**: Expression `expr` must have type `I` exactly (no structural typing)

**Usage:**

```jo
val service = new Service(someLogger)
service.log("hello")

// Member selection process:
// 1. Look for log in Service - not found
// 2. Service has view Logger - found
// 3. Insert: (view service as Logger).log("hello")
```

**Valid delegations:**

```jo
interface Logger
  def log(msg: String): Unit
end

// Case 1: Delegate to interface type (most common)
class Service1(logger: Logger)
  view Logger = logger  // OK: logger has type Logger
end

// Case 2: Delegate to class (requires explicit view selection)
class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger
end

class Service2(logger: FileLogger)
  view Logger = view logger as Logger  // OK: explicit selection
end
```

**Invalid delegation:**

```jo
class Service3(logger: FileLogger)
  view Logger = logger  // Error: FileLogger != Logger
end
```

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

### View Selection

Views can be selected explicitly or implicitly:

**Explicit selection:**

```jo
val point = new Point(10, 20)
val showable: Show = view point as Show
println(showable.show())
```

**Implicit selection via type-directed adaptation:**

```jo
def display(s: Show): Unit = println(s.show())

val point = new Point(10, 20)
display(point)  // Type-directed: view point as Show
```

**Implicit selection during member selection:**

Member selection can find methods through views when direct members are not available. Priority order:

**Priority 1: Direct members always shadow view members**

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger: Logger)
  view Logger = logger

  // Direct member shadows delegated member
  def log(msg: String): Unit =
    println("[SERVICE] " + msg)
    logger.log(msg)  // Can still call delegated version explicitly
end

val s = new Service(someLogger)
s.log("hello")  // Calls Service.log (direct member), NOT delegated logger.log
```

**Priority 2: If no direct member exists, search views**

```jo
interface Show
  def show(): String
end

class Point(x: Int, y: Int)
  // No direct 'show' member, only through view
  view Show = createShowImpl(x, y)
end

val p = new Point(3, 4)
println(p.show())  // OK: finds show() through Show view
```

**Ambiguity only when multiple views provide same member:**

Ambiguity is reported **only if**:

- No direct member exists with that name, AND
- Multiple views provide a member with that name

!!! info "No Overloading"
    Jo does not support method overloading. Methods are identified by name alone, not by signature. Therefore, `write(s: String)` and `write(doc: Doc)` are both considered the same member "write", causing ambiguity when provided by different views.

```jo
interface Writer
  def write(s: String): Unit
end

interface Renderer
  def write(doc: Doc): Unit
end

class Output(writer: Writer, renderer: Renderer)
  // No direct 'write' member
  view Writer = writer
  view Renderer = renderer
end

val out = new Output(someWriter, someRenderer)
out.write("hello")  // Error: Ambiguous - Writer.write or Renderer.write?
```

**Disambiguation by explicit view selection:**

```jo
val writer: Writer = view out as Writer
writer.write("hello")  // OK: unambiguous, uses Writer.write
```

**Overriding eliminates ambiguity:**

```jo
class SmartOutput(writer: Writer, renderer: Renderer)
  view Writer = writer
  view Renderer = renderer

  // Direct member resolves ambiguity
  def write(s: String): Unit = writer.write(s)  // Chooses Writer version
end

val out = new SmartOutput(someWriter, someRenderer)
out.write("hello")  // OK: calls direct member (no ambiguity)
```

### View Object Identity

Jo does not support checking view identity. Similar to how function equality is not supported in many FP languages, view identity is not part of the language semantics:

```jo
val p = new Point(0, 0)
val v1 = view p as Show
val v2 = view p as Show

// Not supported: view identity checks
v1 == v2  // Error: cannot compare interface types for equality
```

This design choice:

- **Simplifies semantics**: No notion of view identity to reason about
- **Implementation freedom**: Backends can optimize as needed (return original object or create wrapper)
- **Avoids confusion**: Views are about behavior, not identity

## Type Checking

### View Conformance Check

When a class declares `view I[T1, ..., Tn]`, verify:

1. **Interface resolution**: `I` resolves to an interface definition
2. **Type parameter arity**: Number of type arguments matches interface parameters
3. **Method coverage**: For each method `m` in interface `I`:
   - Class has a member `m` with compatible signature
   - Signature compatibility includes:
     - Parameter count and types (after substitution)
     - Return type (after substitution)
     - Effect requirements

4. **Default methods**: Methods with default implementations are optional in class

**Duplicate view check:**

A class must not declare the same view twice:

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger1: Logger, logger2: Logger)
  view Logger = logger1
  view Logger = logger2  // Error: Duplicate view declaration for Logger
end
```

This prevents ambiguity about which view implementation to use and which field stores the delegated instance.

**View delegation type checking:**

For `view I = expr`, verify:

1. **Interface resolution**: `I` resolves to an interface definition
2. **Expression type**: `expr` must have type `I` (nominal type checking, no structural typing)
3. **View conformance**: After desugaring, the class must conform to interface `I`

### View Selection

For `view expr as I`:

1. **Expression type**: Compute **compile-time** type `T` of `expr` (must be a class type, not an interface type)
2. **View search**: Check if `T` (or its class symbol) declares `view I`
3. **Type substitution**: If `T` is `C[T1, ..., Tn]` and view is `I[U1, ..., Um]`, apply standard type parameter substitution
4. **Result type**: `I` with appropriate type arguments

!!! info "Compile-Time Class Type Requirement"
    View selection requires the expression to have a compile-time class type. If the expression has an interface type, view selection is not available because interface types do not carry view declaration information.

    ```jo
    def process(logger: Logger): Unit =
      val printer: Printer = view logger as Printer  // Error: logger has interface type
    end
    ```

```jo
interface Show
  def show(): String
end

class Point(x: Int, y: Int)
  def show(): String = "Point(" + intToStr(x) + ", " + intToStr(y) + ")"
  view Show
end

val p: Point = new Point(0, 0)
val s: Show = view p as Show  // Valid: p has class type Point

class NoView
end

val nv = new NoView()
val bad = view nv as Show  // Error: NoView does not declare view Show
```

### Implicit View Adaptation

During type adaptation from `found: C` to `expected: I`:

1. **Direct match**: If `C <: I`, succeed (but this won't happen for views)
2. **View search**: If `C` declares `view I`, insert implicit `view ... as I`
3. **Type parameter unification**: If needed, unify type parameters
4. **Failure**: Report type mismatch

### Member Selection with Views

For `expr.member` where `expr: T`, member selection algorithm:

1. **Direct member lookup**: Search for `member` in type `T`. If found, use it (shadows view members)
2. **View member lookup**: If not found, search all views declared by `T`
    - If exactly one view provides `member`, insert `(view expr as I).member`
    - If multiple views provide `member`, report ambiguity error
3. **Failure**: If no member found in direct or view lookup, report member not found error

See Semantics section for detailed examples of shadowing, ambiguity, and disambiguation.

## Examples

### Basic Interface and View

```jo
interface Show
  def show(): String
end

class Point(x: Int, y: Int)
  def show(): String = "Point(" + intToStr(x) + ", " + intToStr(y) + ")"

  view Show
end

def printShow(s: Show): Unit receives IO.stdout =
  println(s.show())

def main receives IO.stdout =
  val p = new Point(3, 4)
  printShow(p)  // Implicit: view p as Show
```

### Multiple Views

```jo
interface Serializer[T]
  def encode(x: T): String
  def decode(s: String): T
end

interface Comparable[T]
  def compare(x: T, y: T): Int
end

class User(id: Int, name: String)
  def encode(u: User): String = intToStr(u.id) + ":" + u.name

  def decode(s: String): User =
    // Parse string and create User
    ...

  def compare(u1: User, u2: User): Int = u1.id - u2.id

  view Serializer[User]
  view Comparable[User]
end

def serialize[T](value: T, ser: Serializer[T]): String =
  ser.encode(value)

def max[T](a: T, b: T, cmp: Comparable[T]): T =
  if cmp.compare(a, b) > 0 then a else b

def main =
  val alice = new User(1, "Alice")
  val bob = new User(2, "Bob")

  val serialized = serialize(alice, view alice as Serializer[User])
  val winner = max(alice, bob, view alice as Comparable[User])
```

### Generic Interface

```jo
interface Container[T]
  def get(): T
  def set(value: T): Unit
  def isEmpty(): Bool
end

class Box[T](value: T, empty: Bool)
  def get(): T = value
  def set(v: T): Unit = { value = v, empty = false }
  def isEmpty(): Bool = empty

  view Container[T]
end

def processContainer[T](c: Container[T], newValue: T): T =
  if !c.isEmpty() then
    val old = c.get()
    c.set(newValue)
    old
  else
    c.set(newValue)
    newValue

def main =
  val box: Box[Int] = new Box(42, false)
  val result = processContainer(box, 100)  // Implicit view selection
  println(intToStr(result))  // Prints: 42
```

### Default Method Implementation

```jo
interface Monoid[T]
  def combine(x: T, y: T): T
  def identity: T
  def combineAll(xs: List[T]): T =
    var result = identity
    var current = xs
    while !current.isEmpty() do
      result = combine(result, current.head())
      current = current.tail()
    result
end

class IntAddition
  def combine(x: Int, y: Int): Int = x + y
  def identity: Int = 0

  view Monoid[Int]
  // Inherits default combineAll implementation
end

def main =
  val adder: Monoid[Int] = view (new IntAddition()) as Monoid[Int]
  val sum = adder.combineAll([1, 2, 3, 4, 5])
  println(intToStr(sum))  // Prints: 15
```

### View Delegation

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

interface Metrics
  def recordLatency(ms: Int): Unit
  def recordCount(name: String): Unit
end

class FileLogger(path: String)
  def log(msg: String): Unit = ...
  def error(msg: String): Unit = ...
  view Logger
end

class StatsdMetrics(host: String, port: Int)
  def recordLatency(ms: Int): Unit = ...
  def recordCount(name: String): Unit = ...
  view Metrics
end

class UserService(logger: Logger, metrics: Metrics, dbUrl: String)
  // Delegate views to composed instances
  view Logger = logger
  view Metrics = metrics

  def createUser(name: String): User =
    log("Creating user: " + name)  // Delegates to logger.log
    val start = currentTimeMillis()

    val user = createUserInDb(name, dbUrl)

    val elapsed = currentTimeMillis() - start
    recordLatency(elapsed)  // Delegates to metrics.recordLatency
    recordCount("user.created")  // Delegates to metrics.recordCount

    log("User created successfully")
    user

  def createUserInDb(name: String, url: String): User = ...
end

def main =
  val logger = new FileLogger("/var/log/app.log")
  val metrics = new StatsdMetrics("localhost", 8125)
  val service = new UserService(logger, metrics, "db://localhost")

  val user = service.createUser("Alice")

  // Delegation via member selection: service has no log method,
  // but member selection finds it through the Logger view
  service.log("Direct delegation via member selection")
  service.error("Error message via delegation")
  service.recordCount("api.call")

  // Can also select views explicitly
  val asLogger: Logger = view service as Logger
  asLogger.log("Service implements Logger via delegation")
end
```

### Wrapper Pattern for External Types

When you need to add views to types you don't control (e.g., from libraries), use the wrapper pattern:

```jo
// Library defines:
class TreeNode[T](value: T, left: TreeNode[T] | Null, right: TreeNode[T] | Null)

// Your code wants Iterator view
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

// Solution: Create a wrapper class
class TreeIterator[T](root: TreeNode[T])
  var current: List[TreeNode[T]] = [root]

  def hasNext(): Bool = !current.isEmpty

  def next(): T =
    val node = current.head
    current = current.tail
    if node.left != null then current = node.left :: current
    if node.right != null then current = node.right :: current
    node.value

  view Iterator[T]
end

def processTree[T](tree: TreeNode[T]): Unit =
  val iter = new TreeIterator(tree)
  while iter.hasNext() do
    val value = iter.next()
    // process value
end
```

This pattern is explicit and maintains clear ownership boundaries. Jo does not support retroactive views—views must be declared in the class definition.

## Design Decisions

### Why Avoid Inheritance? Composition Over Inheritance

Jo avoids traditional class inheritance entirely, promoting **composition with delegation** instead.

**Problems with inheritance:**

- Fragile base class problem (changes break subclasses)
- Tight coupling to implementation details
- Diamond problem with multiple inheritance
- Forced taxonomies that don't match evolving requirements

**Solution: View delegation**

Use `view I = expr` to delegate interface implementations to composed instances:

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger: Logger, config: Config)
  view Logger = logger  // Delegate to composed instance

  def process(): Unit =
    log("Processing")  // Delegates to logger.log via member selection
end
```

**Benefits:**

- Flexible composition without inheritance hierarchies
- Multiple behaviors without diamond problems
- Clear, explicit dependencies
- Easy testing with mock implementations

### Why View Delegation Does Not Resolve Recursively?

When using view delegation (`view I = expr`), member selection does not recursively search through views of the delegated object. This keeps the type system simple and predictable.

**Non-recursive member selection through delegation:**

```jo
interface Printer
  def print(): Unit
end

interface Logger
  def log(msg: String): Unit
end

class FileLogger(path: String)
  def log(msg: String): Unit = writeFile(path, msg)
  view Logger

  def print(): Unit = println("FileLogger")
  view Printer
end

class Service(logger: FileLogger)
  view Logger = logger  // Delegate Logger view to logger
end

val s = new Service(someFileLogger)
s.log("hello")   // OK: Service has Logger view, delegates to logger.log()
s.print()        // Error: Service does not have print() or Printer view
```

**What happens:**

Member selection on `s.print()` checks:

1. Direct members of `Service` - no `print()` method
2. Views declared by `Service` - only `Logger` view
3. Members of `Logger` interface - no `print()` method in the interface

The search stops here. It does **not** recursively check:

- That the delegated `logger` field has type `FileLogger`
- That `FileLogger` has a `Printer` view with a `print()` method

**To access `print()`, you need explicit composition:**

```jo
class Service(logger: FileLogger)
  view Logger = logger
  view Printer = logger  // Explicitly delegate Printer view too
end

val s = new Service(someFileLogger)
s.print()  // OK: Service declares Printer view, delegates to logger.print()
```

Or select views explicitly:

```jo
val printer: Printer = view s.logger as Printer
printer.print()  // OK: explicit view selection
```

**Rationale:**

- **Predictability**: Members are found only in declared views, not through delegation chains
- **Simplicity**: No need to track types of delegated objects at member selection time
- **Explicit design**: Forces clear declaration of exposed capabilities
- **Type abstraction**: Delegation uses interface types, which don't carry view information

### Why No View Identity?

Jo does not support checking view identity (similar to how function equality is not supported in many FP languages). This gives implementation freedom:

Different platforms can optimize view selection differently:

1. **Dynamic languages (JS/Python)**: May return the object itself
2. **Static languages (Java/Native)**: May create vtable wrappers

Benefits:

- **Implementation freedom**: Each platform can optimize without changing semantics
- **No overhead**: No need to track or compare view identities
- **Simpler semantics**: Views are about behavior, not identity

## Summary

Interfaces define behavioral contracts independently from concrete implementations. Views enable classes to expose multiple interfaces without subtyping relationships, supporting composition over inheritance. View delegation (`view I = expr`) provides automatic method forwarding to composed instances, eliminating boilerplate while maintaining static type safety. This design promotes flexible, maintainable code without the problems of traditional class inheritance.
