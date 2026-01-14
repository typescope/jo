# Classes and Views

## Overview

**Views** provide a unifying philosophy for designing class behaviors through both **subtyping** and **composition**.

The `view` mechanism enables classes to adopt behavior contracts through two complementary approaches:

- **Direct views** (`view I`): Subtyping-based design.
- **Delegate views** (`view I = expr`): Composition-based design.

Both use the same syntactic mechanism and conceptual framework—**views**—but serve different design needs.

## Motivation

### Beyond "Is-a" vs "Has-a"

The traditional dichotomy between **"is-a"** (inheritance/subtyping) and **"has-a"** (composition) is somewhat artificial. In practice, programmers care primarily about **fulfilling behavioral contracts**—ensuring that objects provide the capabilities required by the interfaces they use.

However, traditional OOP languages create a significant **asymmetry**:

- **Subtyping ("is-a")** is easy: declare inheritance, get automatic subtype relationships
- **Composition ("has-a")** is tedious: write forwarding methods and select delegate object

This asymmetry contradicts the widely-accepted principle of **"prefer composition over inheritance"**.

### Entering Views

Views provide a **unifying philosophy** where both approaches are equally easy. The single `view` mechanism enables classes to fulfill behavioral contracts through either **subtyping** or **composition**:

- **Direct views** (`view I`): Fulfill contracts via **subtyping**
    - The class structurally implements the interface, creating `C <: I`
    - Enables polymorphic usage through type compatibility

- **Delegate views** (`view I = expr`): Fulfill contracts via **composition**
    - The class delegates to an instance that provides the behavior
    - No manual forwarding needed—delegation is automatic
    - Enables behavioral reuse without coupling or subtyping

**The same class can use both:**

```jo
interface Loggable
  def log(msg: String): Unit
end

interface Serializable
  def serialize(): String
end

class User(id: Int, name: String, logger: Loggable)
  // Direct view: fulfills Serializable contract via subtyping
  def serialize(): String = "User(" + id + ", " + name + ")"
  view Serializable

  // Delegate view: fulfills Loggable contract via composition
  view Loggable = logger
end
```

### Benefits

This unification provides:

- **Focus on contracts**: Emphasize what behaviors a class provides, not how it's implemented
- **Composition-friendly**: Delegation is now as convenient as subtyping

## Basic Syntax

Classes declare views using the `view` keyword.

### Direct Views

```jo
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

class Range(start: Int, ends: Int)
  def iterator(): Iterator[Int] = new RangeIterator(this)
end

// Separate iterator with its own state
class RangeIterator(range: Range)
  var current: Int = range.start

  def hasNext(): Bool = current < range.ends
  def next(): Int =
    val value = current
    current = current + 1
    value

  view Iterator[Int]
end

val range = new Range(0, 10)
val iter = range.iterator()
while iter.hasNext() do
  println(iter.next())
end
```

### Delegate Views

Views can delegate to concrete instances using `view T = expr`, where `T` can be any interface or class type:

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
    Both mechanisms help classes fulfill behavioral contracts—they differ in how:

    - **Direct views** (`view I`): Fulfill contracts via **subtyping**. Only allowed for interface types. The class must implement all interface methods, creating `C <: I`.
    - **Delegate views** (`view T = expr`): Fulfill contracts via **adaptation**. Allowed for any type (interface or class). Delegates method calls to the expression. Does NOT create subtyping.

## How Views Work

### Direct Views: Subtyping

When a class declares `view I`, it creates a **subtyping relationship** `C <: I`:

```jo
interface Logger
  def log(msg: String): Unit
end

class ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger  // Creates subtyping: ConsoleLogger <: Logger
end

// No adaptation needed
def useLogger(logger: Logger): Unit = logger.log("msg")
val console = new ConsoleLogger()
useLogger(console)  // OK: ConsoleLogger <: Logger
```

### Delegate Views: Composition

When a class declares `view T = expr`, it creates a view field that holds the delegated instance:

```jo
class Service(logger: Logger)
  view Logger = logger  // Creates: val Logger: Logger
end

val service = new Service(someLogger)
service.log("hello")  // Delegates to: service.Logger.log("hello")
```

### Using Views

**Type adaptation:**

```jo
def useLogger(l: Logger): Unit = l.log("msg")

val service = new Service(someLogger)
useLogger(service)  // Implicit adaptation through Logger view
```

**Member selection:**

When you call `service.log("hello")`, the compiler:

1. Looks for `log` directly in `Service` - not found
2. Searches through Service's views - finds `Logger` view
3. Resolves to: `service.Logger.log("hello")`

## Example: Multi-role Delegation

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
  println("Salary: " + salary)
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

## Design Decisions

### View Lookup is Non-Recursive

Both member selection and view adaptation only examine views **directly declared** by a class—they never recursively search through views of delegated objects.

**Why this matters:**

```jo
class FileLogger(path: String)
  def log(msg: String): Unit = ...
  view Logger  // FileLogger has Logger view
end

class Service(logger: FileLogger)
  view FileLogger = logger  // Service gets FileLogger view only
  // Service does NOT automatically get Logger view!
end
```

To expose the Logger view, you must declare it explicitly:

```jo
class Service(logger: FileLogger)
  view FileLogger = logger
  view Logger = logger  // Explicitly expose Logger
end
```

**Benefits:**

- **Explicit control**: Each class explicitly declares which capabilities it exposes
- **Predictability**: Views are found only in direct declarations
- **Encapsulation**: Changes to delegate classes don't silently affect the delegating class
- **Simplicity**: No complex transitive closure computation

### View Consistency and Uniqueness

Classes must maintain consistency in their view declarations to ensure predictable behavior:

- **No duplicate views**: A class cannot declare multiple views of the same type
- **No duplicate members**: The virtual namespace (combining direct members and view-delegated members) must not contain duplicate method or field names
- **View consistency**: Direct views must properly implement all interface members; delegate views must reference values of the correct type

These constraints prevent ambiguity in member selection and type adaptation, ensuring that view-based delegation remains clear and deterministic.

## Technical Details

For detailed technical specifications, see:

- [Class Definitions](../definitions/class-definitions.md) - View fields, member selection rules, and view consistency
- [Interface Definitions](../definitions/interface-definitions.md) - View conformance checking and interface method requirements
