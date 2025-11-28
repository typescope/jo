# Interfaces and Views

## Overview

Interfaces define behavioral contracts independently from implementation.
Views enable classes to expose multiple aspects (interfaces or other classes) without creating subtype hierarchies.
This design supports composition over inheritance while maintaining static type safety.

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

Views support both direct implementation (`view I`) and automatic delegation (`view I = expr`), with type-directed selection and member resolution through interfaces—bringing duck typing's flexibility to a statically typed language while maintaining safety and explicit design.

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
  def equals(x: T, y: T): Bool = compare(x, y) == 0  // Concrete method
end
```

### Class Definition and Initialization

```
class_def = "class" ident type_params params? class_member* "end"
class_member = view_decl | field | method
field = ("val" | "var") ident ":" type ["=" expr]
```

Classes define object templates with fields and methods. Views, fields, and methods can appear in any order. Jo provides two mutually exclusive syntaxes for defining constructors.

**Option 1: Class parameters (syntactic sugar)**

Declare parameters directly after the class name. The compiler generates a constructor automatically:

```jo
class Point(x: Int, y: Int)
  val cachedHash: Int = x * 31 + y

  def show(): String = "Point(" + intToStr(x) + ", " + intToStr(y) + ")"
end

val p = new Point(3, 4)
```

The class parameters (`x`, `y`) become immutable fields. Fields with initializers have their RHS evaluated during construction. This desugars to:

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

Constructor requirements:

- Return type must be the class type if declared
- Body must start with field assignments (`this.field = expr`)
- All fields must be initialized
- RHS of assignments is type-checked without `this` in scope (only parameters available)
- Constructor automatically appends `this` to return the instance

!!! warning "Mutually Exclusive Syntaxes"
    Class parameters and explicit constructors cannot coexist. Use class parameters for simple cases (they generate the constructor), or write an explicit constructor for custom initialization logic.

**Fields with initializers:**

Both approaches support fields with initializers:

```jo
class Counter(initial: Int)
  val count: Int = initial    // RHS can reference constructor parameters
  var total: Int = 0          // RHS is a constant
end
```

Field initializers are evaluated during construction without `this` in scope—only constructor parameters are available.

**Initialization order:**

With class parameters:

1. Constructor parameters assigned to their fields
2. Field initializers evaluated and assigned (in declaration order)
3. Instance returned (automatic `this` append)

With explicit constructor:

1. Field assignments in constructor body
2. Field initializers evaluated and assigned (in declaration order)
3. Remaining constructor statements executed
4. Instance returned (automatic `this` append)

**Immutability:**

Constructor parameters and `val` fields are immutable. Use `var` for mutable fields:

```jo
class Account(id: Int)
  var balance: Int = 0

  def deposit(amount: Int): Unit =
    this.balance = this.balance + amount
end
```

### View Declaration in Classes

```
view_decl = "view" type_tree ["=" expr]
```

Classes declare views using the `view` keyword. With Jo's simplified class syntax, constructor parameters are declared directly:

**Direct views:**

```jo
class Point(x: Int, y: Int)
  // Members implementing Show view
  def show(): String = "Point(" + intToStr(x) + ", " + intToStr(y) + ")"

  // Members implementing Comparable[Point] view
  def compare(p1: Point, p2: Point): Int =
    val dx = p1.x - p2.x
    if dx != 0 then dx else p1.y - p2.y

  // Declare direct views
  view Show
  view Comparable[Point]
end
```

**Multiple direct views:**

```jo
class User(id: Int, name: String)
  def show(): String = "User(" + intToStr(id) + ", " + name + ")"
  def encode(u: User): String = intToStr(u.id) + ":" + u.name
  def decode(s: String): User = ...

  view Show
  view Serializer[User]
end
```

**Delegate views:**

Views can delegate to concrete instances using `view T = expr`, where `T` can be any type (interface or class):

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

class Service(logger: Logger, config: Config)
  view Logger = logger  // Delegate interface view to logger instance

  def process(): Unit =
    log("Starting process")  // Delegates via member selection
end
```

**Delegation to class types:**

```jo
class Employee
  def work(): Unit = ...
  def getSalary(): Int = ...
end

class Customer
  def buy(item: Item): Unit = ...
end

class Person(name: String, empData: Employee, custData: Customer)
  view Employee = empData  // Delegate Employee role to empData
  view Customer = custData  // Delegate Customer role to custData

  def introduce(): String = "Hi, I'm " + name
end
```

!!! info "Direct vs. Delegate Views"
    - **Direct views** (`view T`): Only allowed for interface types. The class must implement all interface methods.
    - **Delegate views** (`view T = expr`): Allowed for any type (interface or class). Methods are resolved through member selection at usage sites.

See the Semantics section for complete delegation semantics.

### View Accessors

Each view declaration creates a field that holds the view instance:

```jo
class User(id: Int, name: String)
  def show(): String = "User(" + intToStr(id) + ", " + name + ")"
  view Show
end

val user = new User(1, "Alice")
val showable: Show = user.Show  // Access view field
val serialized = showable.show()
```

Type annotation triggers implicit view adaptation:

```jo
val user = new User(1, "Alice")
val showable: Show = user  // Implicit view adaptation (equivalent to user.Show)
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
    A class type is NOT a subtype of its views. Views are accessed via explicit accessor (`obj.ViewName`) or via type-directed adaptation. There is no upcasting or downcasting.

### Concrete Method Implementation

Interfaces can provide concrete implementations for methods:

```jo
interface Eq[T]
  def equals(x: T, y: T): Bool                     // Abstract method
  def notEquals(x: T, y: T): Bool = !equals(x, y)  // Concrete method
end

class Counter(count: Int)
  // Only need to implement equals, notEquals is provided by interface
  def equals(c1: Counter, c2: Counter): Bool = c1.count == c2.count

  view Eq[Counter]
end
```

**Important**: Concrete methods (methods with implementations in interfaces) **cannot be overridden** by implementing classes:

```jo
class FastCounter(count: Int)
  def equals(c1: FastCounter, c2: FastCounter): Bool = c1.count == c2.count

  // ERROR: Cannot override concrete method
  def notEquals(c1: FastCounter, c2: FastCounter): Bool = c1.count != c2.count

  view Eq[FastCounter]
end
```

!!! info "Design Rationale"

    Concrete methods in interfaces are **final** to avoid surprises. When you call a method defined in an interface, you know exactly which implementation will execute—the one defined in the interface. This makes reasoning about behavior easier and prevents subtle bugs from unexpected overrides.

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

!!! info "Design Rationale"

    Since Jo has no subtyping between classes and interfaces, variance annotations would add complexity without benefit. Invariance is simpler.

### View Delegation Semantics

View declarations create **fields** that hold view instances, where **T can be any type** (interface or class).

**For `view I` where `I` is an interface (direct view):**

Each `view I` declaration creates a field `val I: I` that holds a compiler-synthesized view instance.

```jo
interface Logger
  def log(msg: String): Unit
end

class ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger  // Creates: val Logger: Logger
end
```

**For `view T = expr` (delegate view):**

Each `view T = expr` declaration creates a field `val T: T` that holds the result of evaluating `expr`.

```jo
class Service(logger: Logger)
  view Logger = logger  // Creates: val Logger: Logger
end
```

**Key properties:**

1. **View fields**: Each `view T` or `view T = expr` creates a field `val T: T`
2. **Single instance**: The same view instance is returned on every access
3. **Evaluation**: For `view T = expr`, expression is evaluated once at construction time
4. **Non-recursive**: Member selection does NOT recursively search through views of the delegated object (see Design Decisions)

**Usage:**

```jo
val service = new Service(someLogger)
service.log("hello")

// Member selection process:
// 1. Look for log in Service directly - not found
// 2. Search through Service's views - Logger view found
// 3. Resolve: service.Logger.log("hello")
```

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

### View Adaptation

Views can be accessed explicitly or implicitly:

**Explicit accessor:**

```jo
val point = new Point(10, 20)
val showable: Show = point.Show
println(showable.show())
```

**Implicit adaptation via type annotation:**

```jo
def display(s: Show): Unit = println(s.show())

val point = new Point(10, 20)
display(point)  // Type-directed adaptation: point.Show
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

**Disambiguation by explicit view accessor:**

```jo
val writer: Writer = out.Writer
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

### Interface Type Equality

Jo does not support equality for interface types. Similar to how function equality is not supported in many FP languages, interface types do not have equality defined:

```jo
val p = new Point(0, 0)
val v1: Show = p.Show
val v2: Show = p.Show

v1 == v2  // Error: equality not defined for interface types
```

This applies to all interface-typed values, regardless of how they were obtained (view accessor, type adaptation, or direct interface-typed expressions).

**Rationale:**

- **Implementation freedom**: Backends can represent interface values differently (original object, wrapper, etc.)
- **Consistency**: Similar to function types and other abstract types without intrinsic identity

## Type Checking

### View Conformance Check

When a class declares `view I[T1, ..., Tn]`, verify:

1. **Interface resolution**: `I` resolves to an interface definition
2. **Type parameter arity**: Number of type arguments matches interface parameters
3. **Method implementation**: For each method `m` in interface `I`:
   - If `m` is abstract (no body), class must have a member `m` with compatible signature
   - If `m` is concrete (has body), class must NOT override it
   - Signature compatibility includes:
     - Parameter count and types (after substitution)
     - Return type (after substitution)
     - Effect requirements

4. **No concrete method override**: Classes cannot override methods with concrete implementations

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

**Delegate view type checking:**

For `view T = expr`, verify:

1. **Type resolution**: `T` can be any type (interface or class)
2. **Expression type**: `expr` must have type `T` (nominal type checking, no structural typing)
3. **View conformance**: If `T` is an interface, the class must conform to interface `T`

### View Accessor

For `expr.ViewName`:

1. **Expression type**: Compute **compile-time** type `C` of `expr` (must be a class type, not an interface type)
2. **View search (non-recursive)**: Check if `C` directly declares `view ViewName`
3. **Type substitution**: If `C` is `C[T1, ..., Tn]` and view is `ViewName[U1, ..., Um]`, apply standard type parameter substitution
4. **Result type**: `ViewName` with appropriate type arguments

!!! warning "Non-Recursive View Search"
    View accessor only checks views **directly declared** by the class. It does NOT recursively search through views of delegated objects. See "Why View Adaptation and Member Selection Are Non-Recursive?" in Design Decisions.

!!! info "Compile-Time Class Type Requirement"
    View accessor requires the expression to have a compile-time class type. If the expression has an interface type, view accessor is not available because interface types do not carry view declaration information.

    ```jo
    def process(logger: Logger): Unit =
      val printer: Printer = logger.Printer  // Error: logger has interface type
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
val s: Show = p.Show  // Valid: p has class type Point

class NoView
end

val nv = new NoView()
val bad = nv.Show  // Error: NoView does not declare view Show
```

### Implicit View Adaptation

During type adaptation from `found: C` to `expected: T`:

1. **Direct match**: If `C <: T`, succeed (but this won't happen for views)
2. **View search (non-recursive)**: If `C` directly declares `view T`, insert implicit view accessor `expr.T`
3. **Type parameter unification**: If needed, unify type parameters
4. **Failure**: Report type mismatch

Note: `T` can be any type (interface or class). View search is **non-recursive**—only views directly declared by `C` are checked.

### Member Selection with Views

For `expr.member` where `expr: C`, member selection algorithm:

1. **Direct member lookup**: Search for `member` in type `C`. If found, use it (shadows view members)
2. **View member lookup (non-recursive)**: If not found, search all views **directly declared** by `C`
    - For each `view T` declared by `C`, check if `T` has `member`
    - If exactly one view provides `member`, resolve to `expr.T.member`
    - If multiple views provide `member`, report ambiguity error
3. **Failure**: If no member found in direct or view lookup, report member not found error

!!! warning "Non-Recursive Member Lookup"
    Member selection only searches views **directly declared** by the class. It does NOT recursively search through views of delegated objects. See Design Decisions for rationale.

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
  printShow(p)  // Implicit: p.Show
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

  val serialized = serialize(alice, alice.Serializer)
  val winner = max(alice, bob, alice.Comparable)
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

### Concrete Method Implementation

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
  // Inherits concrete combineAll implementation
end

def main =
  val addition = new IntAddition()
  val adder: Monoid[Int] = addition.Monoid  // Access view via accessor
  val sum = adder.combineAll([1, 2, 3, 4, 5])
  println(intToStr(sum))  // Prints: 15
```

### Delegate Views

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

  // Can also access views explicitly
  val asLogger: Logger = service.Logger
  asLogger.log("Service implements Logger via delegation")
end
```

### Delegate Views to Class Types

Classes can delegate to other class types to model multiple roles:

```jo
class Employee(id: Int)
  def work(): Unit = println("Working...")
  def getSalary(): Int = 50000
end

class Customer(level: Int)
  def buy(item: String): Unit = println("Buying " + item)
  def getDiscountLevel(): Int = level
end

// Person plays multiple roles
class Person(name: String, empData: Employee, custData: Customer)
  view Employee = empData  // Expose Employee role
  view Customer = custData  // Expose Customer role

  def introduce(): String = "Hi, I'm " + name
end

def processPayroll(e: Employee): Unit =
  val salary = e.getSalary()
  println("Salary: " + intToStr(salary))
end

def applyDiscount(c: Customer): Int =
  c.getDiscountLevel()
end

val person = new Person("Alice", new Employee(123), new Customer(2))

// Type-directed adaptation to class types
processPayroll(person)  // Adapts via view Employee
applyDiscount(person)   // Adapts via view Customer

// Member selection through class views
person.work()           // Finds work() through Employee view
person.buy("book")      // Finds buy() through Customer view
person.introduce()      // Direct member

// Explicit view access
val asEmp: Employee = person.Employee
val asCust: Customer = person.Customer
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

Jo avoids traditional class inheritance entirely, promoting **composition with delegate views** instead.

**Problems with inheritance:**

- Fragile base class problem (changes break subclasses)
- Tight coupling to implementation details
- Diamond problem with multiple inheritance
- Forced taxonomies that don't match evolving requirements

**Solution: Delegate views**

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

### Why View Adaptation and Member Selection Are Non-Recursive?

**Core Principle**: Both view adaptation and member selection operate **non-recursively**—they only examine views directly declared by a class, never recursively searching through views of delegated objects.

This is particularly important for **delegate views to class types**, which allows delegating to objects that themselves have views.

**The Issue: Transitive Views**

Since delegate views now allow class types, a delegated object might have its own views. The question is: should those views be transitively exposed?

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
  view FileLogger = logger  // Delegate to CLASS type (not interface)
end

val s = new Service(someFileLogger)
s.log("hello")   // Error: Service does NOT have view Logger
s.print()        // Error: Service does NOT have view Printer
```

**What happens:**

When `Service` declares `view FileLogger = logger` where `logger: FileLogger`:

- Service gets the FileLogger view (explicitly declared)
- Service does **NOT** get the Logger or Printer views (even though FileLogger has them)

Member selection and view adaptation check only **direct view declarations**, not views of the delegated object.

**Solution: Explicitly declare all exposed views**

If you want to expose FileLogger's views (Logger and Printer), declare them explicitly:

```jo
class Service(logger: FileLogger)
  view FileLogger = logger  // Expose FileLogger
  view Logger = logger      // Explicitly expose Logger
  view Printer = logger     // Explicitly expose Printer
end

val s = new Service(someFileLogger)
s.log("hello")  // OK: Service declares view Logger
s.print()       // OK: Service declares view Printer
```

This makes it explicit which capabilities Service exposes, regardless of what the delegated object supports.

**Alternative: Access via the delegation field**

```jo
// Access Printer through the delegated field
val s = new Service(someFileLogger)
val printer: Printer = s.logger.Printer
printer.print()  // OK: explicit view accessor from delegated object
```

**Non-recursive view adaptation:**

The same non-recursive principle applies to type adaptation:

```jo
def useFileLogger(fl: FileLogger): Unit = ...
def useLogger(logger: Logger): Unit = ...
def usePrinter(printer: Printer): Unit = ...

class Service(logger: FileLogger)
  view FileLogger = logger  // Only expose FileLogger
end

val s = new Service(someFileLogger)
useFileLogger(s) // OK: Service declares view FileLogger
useLogger(s)     // Error: Service does NOT declare view Logger
                 // (not found transitively through FileLogger)
usePrinter(s)    // Error: Service does NOT declare view Printer
                 // (not found transitively through FileLogger)
```

**Rationale:**

- **Explicit over implicit**: Each class explicitly declares which capabilities it exposes, not inheriting them transitively, enabling encapsulation
- **Predictability**: Views are found only in direct declarations, making the API surface clear
- **Simplicity**: No complex transitive closure computation; constant-time view lookup
- **Avoids fragile composition**: Changes to the delegated class (adding/removing views) don't silently change the delegating class's interface

### Why No Equality for Interface Types?

Jo does not define equality for interface types (similar to how function equality is not supported in many FP languages). This design choice provides implementation freedom:

Different platforms can represent interface-typed values differently:

1. **Dynamic languages (JS/Python)**: May use the original object
2. **Static languages (Java/Native)**: May create wrappers with vtables

Benefits:

- **Implementation freedom**: Each platform can choose optimal representation without affecting semantics
- **Behavioral focus**: Interface types represent capabilities (what can be done), not object identity (what it is)
- **Simpler semantics**: No need to define or track identity for abstract interface values

## Summary

Interfaces define behavioral contracts independently from concrete implementations. Views enable classes to expose multiple interfaces without subtyping relationships, supporting composition over inheritance. Delegate views (`view I = expr`) provide automatic method forwarding to composed instances, eliminating boilerplate while maintaining static type safety. This design promotes flexible, maintainable code without the problems of traditional class inheritance.
