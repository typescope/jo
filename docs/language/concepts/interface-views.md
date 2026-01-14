# Classes, Interfaces and Views

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

## Syntax

### Interface Definition

Interfaces define pure behavioral contracts:

```jo
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

interface Serializer[T]
  def serialize(value: T, writer: Writer): Unit
  def deserialize(reader: Reader): T
end

interface Comparable[T]
  def compare(x: T, y: T): Int
  def equals(x: T, y: T): Bool = compare(x, y) == 0  // Concrete method
end
```

### View Declaration in Classes

Classes declare views using the `view` keyword.

**Direct views:**

```jo
// Immutable Range class with Comparable view
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

**Delegate views:**

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

See the Semantics section for complete delegation semantics and subtyping behavior.

### View Accessors

The views of a class can be accessed explicitly using view accessors:

```jo
class RangeIterator(range: Range)
  var current: Int = range.start
  def hasNext(): Bool = current < range.end
  def next(): Int =
    val value = current
    current = current + 1
    value
  view Iterator[Int]
end

val range = new Range(0, 10)
val iter = range.iterator()
val iterView: Iterator[Int] = iter.view[Iterator[Int]]  // Access view explicitly
val first = iterView.next()
```

Type annotation triggers implicit view adaptation:

```jo
val range = new Range(0, 10)
val iter = range.iterator()
val iterView: Iterator[Int] = iter  // Implicit view adaptation (equivalent to iter.view[Iterator[Int]])
```

## Semantics

### View Semantics: Direct vs Delegate

View declarations come in two forms with different semantics:

#### Direct Views: Subtyping

**For `view I` where `I` is an interface (direct view):**

Creates a **subtyping relationship** `C <: I`. The class type becomes a subtype of the interface type:

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

#### Delegate Views: Composition

**For `view T = expr` (delegate view):**

Each `view T = expr` declaration creates a field `val T: T` that holds the result of evaluating `expr`.

```jo
class Service(logger: Logger)
  view Logger = logger  // Creates: val Logger: Logger
end
```

**Key properties:**

1. **View fields**: Delegate views (`view T = expr`) create an immutable field `val T: T`. Direct views (`view I`) do NOT create fields—they only establish subtyping.
2. **Single instance**: The same view instance is returned on every access
3. **Evaluation**: For `view T = expr`, expression is evaluated once at construction time

!!! warning "Non-Recursive View Search (Important!)"
    Both **member selection** and **view adaptation** are **non-recursive**—they only examine views directly declared by a class, never recursively searching through views of delegated objects.

    This means if you delegate to a class type that has its own views, those transitive views are NOT automatically exposed. You must explicitly declare each view you want to expose.

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

    See "Why View Adaptation and Member Selection Are Non-Recursive?" in Design Decisions for detailed rationale.

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

### View Selection

**Subtyping:**

With direct views, classes are subtypes of their interfaces:

```jo
interface Iterator[Int]
  def hasNext(): Bool
  def next(): Int
end

class RangeIterator(start: Int, end: Int)
  var current: Int = start
  def hasNext(): Bool = current < end
  def next(): Int =
    val value = current
    current = current + 1
    value
  view Iterator[Int]  // Creates RangeIterator <: Iterator[Int]
end

def processAll(iter: Iterator[Int]): Unit =
  while iter.hasNext() do
    println(iter.next())

val iter = new RangeIterator(1, 10)
processAll(iter)  // OK: RangeIterator <: Iterator[Int] (subtyping)
```

**Adaptation:**

Delegate views do NOT create subtyping, adaptation automatically happen with
respect to the target type:

```jo
class Service(logger: Logger)
  view Logger = logger  // Delegate view (no subtyping)
end

def useLogger(l: Logger): Unit = l.log("msg")

val service = new Service(someLogger)
useLogger(service)  // Implicit adaptation: service.view[Logger]
```

**Explicit accessor:**

You can use view accessors for upcasting or disambiguation:

```jo
val iter = new RangeIterator(1, 10)
val iterView: Iterator[Int] = iter.view[Iterator[Int]]  // Explicit upcast
```

!!! info "Restriction: Interface Type Equality Not Supported"
    Jo does not support equality for interface types. Similar to how function equality is not supported in many FP languages, interface types do not have equality defined:

    ```jo
    val r = new Range(0, 10)
    val iter1: Iterator[Int] = r.Iterator
    val iter2: Iterator[Int] = r.Iterator

    iter1 == iter2  // Error: equality not defined for interface types
    ```

    This applies to all interface-typed values, regardless of how they were obtained (view accessor, type adaptation, or direct interface-typed expressions).

### Member Selection

Member selection follows a priority order to resolve which member to use:

**Priority 1: Direct class members always have precedence**

Members defined directly in the class (fields and methods) always take precedence over members accessible through views:

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger: Logger)
  view Logger = logger

  // Direct member has precedence over view member
  def log(msg: String): Unit =
    println("[SERVICE] " + msg)
    logger.log(msg)  // Can still call view member explicitly
end

val s = new Service(someLogger)
s.log("hello")  // Calls Service.log (direct member), NOT view member
```

**Priority 2: Search all views (both direct and delegate)**

If no direct class member exists, member selection searches all views—both **direct view concrete methods** and **delegate view members**. If multiple views provide the same member name, an ambiguity error is reported:

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
  view Writer = writer       // Delegate view
  view Renderer = renderer   // Delegate view
end

val out = new Output(someWriter, someRenderer)
out.write("hello")  // Error: Ambiguous - Writer.write or Renderer.write?
```

**Ambiguity between direct and delegate views:**

Concrete methods from direct views and members from delegate views are searched together, so conflicts between them also cause ambiguity:

```jo
interface Loggable
  def log(msg: String): Unit = println("[DEFAULT] " + msg)  // Concrete method
end

interface Logger
  def log(msg: String): Unit
end

class Service(logger: Logger)
  view Loggable              // Direct view with concrete log() method
  view Logger = logger       // Delegate view with log() method
end

val s = new Service(someLogger)
s.log("hello")  // Error: Ambiguous - Loggable.log or Logger.log?
```

**Disambiguation by explicit view accessor:**

Use the view accessor syntax to explicitly select which view to use:

```jo
val out = new Output(someWriter, someRenderer)
out.Writer.write("hello")      // OK: explicitly uses Writer view
out.Renderer.write(someDoc)    // OK: explicitly uses Renderer view
```

**Direct member eliminates ambiguity:**

Defining a direct member with the same name resolves the ambiguity:

```jo
class SmartOutput(writer: Writer, renderer: Renderer)
  view Writer = writer
  view Renderer = renderer

  // Direct member has precedence, resolves ambiguity
  def write(s: String): Unit = writer.write(s)
end

val out = new SmartOutput(someWriter, someRenderer)
out.write("hello")  // OK: calls direct member (no ambiguity)
```


## Type Checking

### View Conformance

When a class declares `view I[T1, ..., Tn]` (direct view), verify:

1. **Interface resolution**: `I` resolves to an interface definition
2. **Type parameter arity**: Number of type arguments matches interface parameters
3. **Method implementation**: For each method `m` in interface `I`:
     - If `m` is abstract (no body), class must have a member `m` with compatible signature
     - If `m` is concrete (has body):
         - Class must NOT have a method with the same name
         - Class must NOT have a field with the same name
     - Signature compatibility includes:
         - Parameter count and types (after substitution)
         - Return type (after substitution)
         - Effect requirements

**View type requirements:**

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

**Rationale: Coherence in type adaptation**

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
3. **Clear member resolution**: Member selection through views is unambiguous when multiple views provide the same member name

This is critical for **coherence in type adaptation**: given a class type and a target interface/class type, there is at most one applicable view, making the adaptation deterministic and predictable. See "View Consistency" for the duplicate view check.

**Delegate view type checking:**

For `view T = expr`, verify:

1. **Type resolution**: `T` can be only interface or class types
2. **Expression type**: `expr` must have type `T`

### View Consistency

When a class uses views (both direct and delegate), the compiler enforces **View Consistency** to ensure predictable and unambiguous behavior. This includes two rules:

#### 1. No Duplicate Views

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

!!! note "Why View Types Must Be Nominal Types"
    View types must be **interface or class types**, not type aliases. This restriction prevents ambiguity when type aliases point to the same underlying type. See "View Conformance Check" for details.

#### 2. Unique Method Names in Unified Namespace

All visible methods across the class and its views form a **unified virtual namespace**. **Method names must be unique** across:

1. **Direct methods** in the class
2. **Concrete methods** from direct views (interface methods with implementations)
3. **Non-private methods** from delegate views

This prevents confusion about which method gets called and ensures a consistent API regardless of how the object is accessed.

```jo
interface Logger
  def log(msg: String): Unit
end

class FileLogger
  def log(msg: String): Unit = ...
  view Logger
end

// ERROR: Method name conflict
class Service
  def log(msg: String): Unit = ...     // Class method
  view Logger = new FileLogger()        // Delegate view also has log()
  // When calling service.log(), which one executes?
end
```

The compiler rejects this because `log` appears in both:

- As a direct method in `Service`
- As a method in the delegate view `Logger`

**Valid alternatives:**

```jo
// Option 1: Use different method names
class Service
  def logToFile(msg: String): Unit = ...  // Unique name
  view Logger = new FileLogger()           // Has log()
end

// Option 2: Don't define the conflicting method - use the delegate
class Service
  view Logger = new FileLogger()  // Only log() from delegate
end

// Option 3: Use direct view instead (if you want your own implementation)
class Service
  def log(msg: String): Unit = ...  // Your implementation
  view Logger                       // Direct view - subtyping
end
```

This check also catches conflicts between:

- Class methods and concrete methods from direct views
- Concrete methods from multiple direct views
- Methods from multiple delegate views

```jo
interface Flushable
  def flush(): Unit = ...  // Concrete method
end

// ERROR: flush conflicts
class Buffer
  def flush(): Unit = ...  // Class method conflicts with Flushable.flush
  view Flushable
end
```

!!! info "Design Rationale"

    **View Consistency ensures predictable behavior**:

    - **No duplicate views** ensures deterministic type adaptation: given a class type and a target interface type, there is at most one applicable view.
    - **Unique method names** ensures unambiguous method resolution: the same method name doesn't have different implementations depending on how you access the object.

    Together, these rules make view behavior predictable and easy to understand, avoiding the principle of least surprise.


### View Accessor

For `expr.view[V]`:

1. **Expression type**: Compute **compile-time** type `C` of `expr` (must be a class type, not an interface type)
2. **View search (non-recursive)**: Check if `C` directly declares `view V`
3. **Type substitution**: If `C` is `C[T1, ..., Tn]` and view `V` is `F[U1, ..., Um]`, apply standard type parameter substitution
4. **Result type**: `V`

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
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

class Range(start: Int, end: Int)
  var current: Int = start
  def hasNext(): Bool = current < end
  def next(): Int =
    val value = current
    current = current + 1
    value
  view Iterator[Int]
end

val r: Range = new Range(0, 10)
val iter: Iterator[Int] = r.Iterator  // Valid: r has class type Range

class NoView
end

val nv = new NoView()
val bad = nv.Iterator  // Error: NoView does not declare view Iterator
```

### Implicit View Selection

During type adaptation from `expr: C` to `expected: T`:

1. **Direct match**: If `C <: T`, succeed
     - This includes direct views: if `C` declares `view I`, then `C <: I`

2. **Delegate view search (non-recursive)**: If `C` directly declares `view T = expr` (delegate view),
     - compiler automatically select view `expr.view[T]`

!!!info "View search is **non-recursive** and **exact**"

    To trigger implicit delegate view selection, only views directly declared
    in the class are checked. Users to make indirect views available for
    adaptation explicitly with an additional view declaration.

    In addition, the target type must exactly match the view type. Subtyping can
    leak the nested interfaces of the delegate views and misinterpret user's
    intent if the target type is `C | D`.

    Rationale: View adaptation is too powerful a mechanism. Users need to make their
    intent clear.

### Member Selection with Views

For `expr.member` where `expr: C`, member selection algorithm:

1. **Direct member lookup**: Search for `member` in type `C`. If found, use it.
2. **View member lookup (non-recursive)**: If not found, search all views (direct and delegate).
     - For each `view T` declared by `C`, check if `T` has `member`
     - If exactly one view provides `member`, resolve to `expr.T.member`
     - If multiple views provide `member`, report ambiguity error

!!! warning "Non-Recursive Member Lookup"
    Member selection only searches views **directly declared** by the class. It does NOT recursively search through views of delegated objects. See Design Decisions for rationale.

See Semantics section for detailed examples of shadowing, ambiguity, and disambiguation.

## Examples

### Basic Interface and View

```jo
interface Iterator[T]
  def hasNext(): Bool
  def next(): T
end

class Range(start: Int, end: Int)
  var current: Int = start

  def hasNext(): Bool = current < end
  def next(): Int =
    val value = current
    current = current + 1
    value

  view Iterator[Int]
end

def sumAll(iter: Iterator[Int]): Int =
  var total = 0
  while iter.hasNext() do
    total = total + iter.next()
  total

def main =
  val r = new Range(1, 11)  // 1..10
  val sum = sumAll(r)       // Implicit: r.Iterator
  println(sum)              // Prints: 55
```

### Multiple Views

```jo
interface Cache[K, V]
  def get(key: K): Option[V]
  def put(key: K, value: V): Unit
  def contains(key: K): Bool
  def clear(): Unit
end

interface Metrics
  def recordHit(): Unit
  def recordMiss(): Unit
  def getHitRate(): Float
end

class LRUCache[K, V](capacity: Int)
  var store: Map[K, V] = emptyMap()
  var hits: Int = 0
  var misses: Int = 0

  // Cache implementation
  def get(key: K): Option[V] =
    store.lookup(key) match
      case Some(v) =>
        recordHit()
        Some(v)
      case None =>
        recordMiss()
        None

  def put(key: K, value: V): Unit =
    store = store.insert(key, value)
    // Evict if over capacity (simplified)
    if store.size() > capacity then
      store = store.removeOldest()

  def contains(key: K): Bool = store.contains(key)
  def clear(): Unit = store = emptyMap()

  // Metrics implementation
  def recordHit(): Unit = hits = hits + 1
  def recordMiss(): Unit = misses = misses + 1
  def getHitRate(): Float =
    val total = hits + misses
    if total == 0 then 0.0 else intToFloat(hits) / intToFloat(total)

  view Cache[K, V]
  view Metrics
end

def cacheData[K, V](key: K, value: V, cache: Cache[K, V]): Unit =
  cache.put(key, value)

def reportStats(metrics: Metrics): Unit =
  val rate = metrics.getHitRate()
  println("Cache hit rate: " + floatToStr(rate))

def main =
  val cache = new LRUCache[String, Int](100)

  cacheData("user:1", 42, cache)  // Uses Cache view
  reportStats(cache)               // Uses Metrics view
```

### Generic Interface with Cache

```jo
interface Cache[K, V]
  def get(key: K): Option[V]
  def put(key: K, value: V): Unit
  def remove(key: K): Bool
  def contains(key: K): Bool
  def clear(): Unit
end

class MemoryCache[K, V]
  var store: Map[K, V] = emptyMap()

  def get(key: K): Option[V] = store.lookup(key)
  def put(key: K, value: V): Unit =
    store = store.insert(key, value)
  def remove(key: K): Bool =
    val exists = store.contains(key)
    store = store.delete(key)
    exists
  def contains(key: K): Bool = store.contains(key)
  def clear(): Unit = store = emptyMap()

  view Cache[K, V]
end

def cacheOrCompute[K, V](key: K, compute: Unit -> V, cache: Cache[K, V]): V =
  cache.get(key) match
    case Some(v) => v
    case None =>
      val computed = compute()
      cache.put(key, computed)
      computed

def main =
  val cache = new MemoryCache[String, Int]()
  val result1 = cacheOrCompute("answer", () => 42, cache)  // Computes
  val result2 = cacheOrCompute("answer", () => 99, cache)  // From cache
  println(result1)  // Prints: 42
  println(result2)  // Prints: 42 (cached)
```

### Concrete Method Implementation

```jo
interface Iterator[T]
  def hasNext(): Bool          // Abstract method
  def next(): T                // Abstract method
  def forEach(f: T -> Unit): Unit =  // Concrete method with default implementation
    while hasNext() do
      f(next())
end

class Range(start: Int, end: Int)
  var current: Int = start

  // Only need to implement hasNext and next
  def hasNext(): Bool = current < end
  def next(): Int =
    val value = current
    current = current + 1
    value

  view Iterator[Int]
  // Inherits concrete forEach implementation
end

def main receives IO.stdout =
  val range = new Range(1, 6)
  val iter: Iterator[Int] = range.Iterator
  iter.forEach(x => println(x))  // Uses inherited forEach
  // Prints: 1, 2, 3, 4, 5 (each on a new line)
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

### Restricting Exposure Surface of Delegate Views

When composing with classes that have many methods, you can selectively control
the API surface by introducing an intermediate class:

```jo

// LayoutEngine has many operations, provided by a 3rd party
class LayoutEngine(tree: Tree)
  def layout(): Unit = ...
  def refresh(): Unit = ...
  def height: Int = ...
  def width: Int = ...
end


// Restrict the interface of a 3rd party class
class Dimension
  private val engine: LayoutEngine

  def Dimension(engine: LayoutEngine) =
    this.engine = engine

  def height: Int = engine.height
  def width: Int = engine.width
end

// Page only exposes the Dimension view, hiding layout operations
class Page(tree: Tree)
  private val layoutEngine = new LayoutEngine(tree)

  view Dimension = new Dimension(layoutEngine) // Expose only Dimension, not full LayoutEngine

  def render(): Unit =
    layoutEngine.layout()  // Internal use of full LayoutEngine
end

// Function that works with any Dimension
def printSize(d: Dimension): Unit =
  println("Size: " + d.width + " x " + d.height)

def main receives IO.stdout =
  val page = new Page(someTree)

  // Can access dimension information
  printSize(page)           // OK: Page has Dimension view
  println(page.height)      // OK: height accessible via Dimension view
  println(page.width)       // OK: width accessible via Dimension view

  // Cannot access LayoutEngine operations
  // page.layout()          // Error: layout() not accessible (no LayoutEngine view)
  // page.refresh()         // Error: refresh() not accessible

  // Full LayoutEngine not exposed
  // val engine: LayoutEngine = page.layoutEngine  // Error: layoutEngine is private
end
```

**Benefits of this pattern:**

- **Selective exposure**: `Page` delegates to `LayoutEngine` but only exposes the `Dimension` interface
- **Encapsulation**: Layout operations (layout, refresh) remain internal to `Page`
- **Clear interface**: Users of `Page` see only dimension queries, not layout operations

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

### Views Unify Subtyping and Composition (No Class Inheritance)

Jo avoids traditional **class inheritance** entirely. Instead, the unified **view mechanism** provides both subtyping (through interfaces) and composition (through delegation).

**Problems with class inheritance:**

- Fragile base class problem (changes break subclasses)
- Tight coupling to implementation details
- Diamond problem with multiple inheritance
- Forced taxonomies that don't match evolving requirements
- Conflates two concerns: subtyping and code reuse

**Jo's solution: Views unify both needs**

- **Subtyping need**: Use direct views (`view I`) for interface subtyping
- **Code reuse need**: Use delegate views (`view I = expr`) for composition

Delegate views provide the code reuse benefits of inheritance through composition:

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

## Summary

**Views** unify subtyping and composition under a single conceptual framework for fulfilling behavioral contracts:

- **Direct views** (`view I`): Create subtyping relationships (`C <: I`)
- **Delegate views** (`view I = expr`): Enable composition with automatic member delegation

This unification provides conceptual simplicity while supporting both design approaches.
