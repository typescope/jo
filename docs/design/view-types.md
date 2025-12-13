# View Types

## Overview

View types enable extending types with new behaviors through **extension views**, allowing types to be augmented with additional interfaces or classes without modifying their original definitions. This complements duck types (flexible adaptation) and intrinsic views (capabilities defined within classes).

**Syntax:**

```jo
type RichPoint = view Point as
  Drawable with pointToDrawable,
  Serializable with pointToSerializable
```

This extends `Point` with `Drawable` and `Serializable` views. The adapters `pointToDrawable` and `pointToSerializable` convert Point values to the respective view types.

**Usage:**

```jo
def process(p: RichPoint): Unit =
  p.draw()       // Access Drawable's methods
  p.serialize()  // Access Serializable's methods
  p.distance()   // Access Point's direct methods
```

View types bring extensibility to statically typed code—adding new capabilities to existing types without modification.

## Motivation

Real-world software often needs to extend library types with new behaviors. While many languages provide extension mechanisms (Swift extensions, Rust traits, Scala implicits, Haskell type classes), these create a fundamental tension between **local reasoning** and **flexibility**.

**The implicit extension problem:**

In languages with implicit extensions (Swift, Rust, Scala, Haskell), the compiler searches for applicable extensions in lexical or global scope. This creates two critical problems:

1. **Poor local reasoning** - You cannot determine what methods are available on a value without examining imports and lexical scope. The same expression `point.draw()` may succeed or fail depending on what's imported in the current module.

2. **Coherence vs rigidity tradeoff**:

     - **Coherence**: Ensure unique extension resolution (Haskell's approach) by rejecting "orphan instances", but this rigidly restricts where extensions can be defined
     - **Flexibility**: Allow extensions anywhere (Scala's approach), but risk incoherence where different parts of the program see different extensions for the same type

**Jo's solution: Explicit extension in types**

View types make extensions explicit in the type declaration itself, not searched from scope:

```jo
// Extensions are declared in the type, not searched from scope
type RichPoint = view Point as
  Drawable with pointToDrawable,
  Serializable with pointToSerializable

def processPoint(p: RichPoint): Unit =
  p.draw()       // Available because RichPoint declares Drawable view
  p.serialize()  // Available because RichPoint declares Serializable view

// Different context can define different extensions for Point
type MinimalPoint = view Point as
  Serializable with pointToSerializable  // Only Serializable, no Drawable

def save(p: MinimalPoint): Unit =
  p.serialize()  // OK: MinimalPoint has Serializable
  // p.draw()    // Error: MinimalPoint does not declare Drawable view
```

**Why this matters:**

1. **Local reasoning** - Looking at `RichPoint`'s declaration tells you exactly what capabilities it has. No need to check imports or lexical scope.

2. **Coherence without rigidity** - Each view type explicitly declares its extensions. Multiple view types can extend the same base type differently without conflict:

     - `RichPoint` has both Drawable and Serializable
     - `MinimalPoint` has only Serializable
     - No global coherence problem because extensions are part of the type

3. **Explicit composition** - You choose which extensions to include when defining each view type, making dependencies clear.

4. **No scope pollution** - Importing a module doesn't implicitly add methods to existing types. Extensions only apply where you use the view type.

View types provide the extensibility of Swift/Scala/Haskell extensions while preserving local reasoning and avoiding coherence problems.

## Syntax

```
view_type = "view" type "as" view_list
view_list = view_spec {"," view_spec}
view_spec = type ["[" "with" adapter "]"]
adapter = qualid
```

View types can be used inline or named via type aliases:

```jo
// Named definition
type RichInt = view Int as
  Comparable with intComparator,
  Printable with intToPrintable

// Inline usage (less common)
def process(x: view Foo as Bar with fooToBar): Unit = ...
```

**View specifications:**

Each view in the list specifies a target type and optional adapter:

1. **With explicit adapter** - `ViewType [with adapterFunc]`
2. **With implicit constructor** - `ViewClass` (class must have constructor taking underlying type)

**Examples:**

```jo
// Multiple views with explicit adapters
type EnhancedString = view String as
  Comparable with stringComparator,
  Iterable with stringToIterable,
  Reversible with stringToReversible

// Mixed: some with adapters, some with constructors
type RichData = view Data as
  Validator with dataValidator,    // Explicit adapter
  Wrapper,                          // Constructor: new Wrapper(data)
  Serializable with dataSerializer  // Explicit adapter

// Single view
type ComparableInt = view Int as Comparable with intComparator
```

## Semantics

### Type Structure

A view type `view T as V1 [with f1], V2 [with f2], ...` consists of:

- **Underlying type** `T` - The base type being extended
- **Extension views** `V1, V2, ...` - Additional capabilities
- **View adapters** `f1, f2, ...` - Functions mapping `T` to each view type

The view type represents values of type `T` augmented with capabilities from all listed views.

### Type Equivalence and Conformance

**View types and their underlying types have mutual conformance:**

```jo
type RichFoo = view Foo as Bar with fooToBar

val x: RichFoo = someFoo      // OK: Foo conforms to RichFoo
val y: Foo = someRichFoo      // OK: RichFoo conforms to Foo
```

This is identical to how duck types work—no runtime distinction, no wrapper overhead.

**View types do NOT create subtyping, but automatic adaptation applies:**

```jo
type RichFoo = view Foo as Bar with fooToBar

def takesBar(b: Bar): Unit = ...

val x: RichFoo = someFoo
takesBar(x)  // OK: Automatic adaptation from RichFoo to Bar (extension view)
```

While `RichFoo` is not a subtype of `Bar`, values of type `RichFoo` are automatically adapted to `Bar` during type checking because `RichFoo` declares `Bar` as an extension view. This is not subtyping—it's type-directed view adaptation.

### View Adapters

**View adapters** are functions that create view instances from the underlying type.

**Requirements:**

- Must be a named function (not lambda or variable)
- Exactly one regular parameter matching the underlying type
- Return type must match the view type (or be a subtype)
- Context parameters are allowed
- No type parameters
- No auto parameters

**Examples:**

```jo
// Valid adapters
def fooToBar(f: Foo): Bar = new Bar(f.value)
def fooToBaz(f: Foo): Baz receives logger = new Baz(f, logger)

// Invalid adapters
def badAdapter(f: Foo, x: Int): Bar = ...           // Multiple parameters
def badAdapter[T](f: T): Bar = ...                  // Type parameter
def badAdapter(auto f: Foo => Bar): Bar = ...       // Auto parameter
```

**Adapter with context parameters:**

```jo
param formatter: Formatter

def fooToString(f: Foo): String receives formatter =
  formatter.format(f.value)

type PrintableFoo = view Foo as Printable with fooToString

def display(p: PrintableFoo): Unit receives formatter =
  println(p.toString)  // Context parameter propagated through adapter
```

**Constructor as adapter:**

If no adapter is specified, the view type must be a class with a constructor accepting the underlying type:

```jo
class Wrapper(foo: Foo)
  def unwrap(): Foo = foo
end

type WrappedFoo = view Foo as Wrapper  // Uses: new Wrapper(foo)
```

### Member Resolution

Member selection on view types follows a priority-based algorithm to resolve ambiguity between direct members and views.

**Resolution rules for `value.member` where `value: view T as V1, V2, ...`:**

1. **Direct members**: If a direct member exists in `T`, use it.
2. **All views**: Search through both intrinsic views and extension views:
    - If only one view provides the member, use it.
    - If multiple views provide the member, report ambiguity error.
    - If no views provide the member, report member not found error.

!!! info "Rationale for treating intrinsic and extension views equally"
    This forces explicit disambiguation when there are conflicts between any views, avoiding subtle bugs from implicit precedence rules.

**Disambiguation:**

Use explicit view accessor `value.view[ViewType]` to resolve ambiguity.

**Example: Direct members shadow views**

```jo
interface Logger
  def log(msg: String): Unit
end

interface Printer
  def print(msg: String): Unit
  def log(msg: String): Unit  // Name collision with Logger
end

class Foo
  def log(msg: String): Unit = println("[FOO] " + msg)  // Direct member

  def show(): Unit = println("showing")
  view Printer = createPrinter()  // Intrinsic view with print() and log()
end

def fooToLogger(f: Foo): Logger = createLogger(f)

type RichFoo = view Foo as Logger with fooToLogger

val x: RichFoo = new Foo()

// Member resolution:
x.log("hello")   // Priority 1: Uses Foo's direct log() method
                 // (Both Logger and Printer views have log, but direct member wins)

x.print("hi")    // Priority 2: Uses Printer's print() via intrinsic view
                 // (No direct member, only Printer view has it)

x.show()         // Priority 1: Uses Foo's direct show() method
```

**Example: Ambiguity between views**

```jo
interface Drawable
  def render(): Unit
end

interface Renderable
  def render(): Unit
end

// Ambiguity between two extension views
def fooToDrawable(f: Foo): Drawable = ...
def fooToRenderable(f: Foo): Renderable = ...

type RichFoo = view Foo as
  Drawable with fooToDrawable,
  Renderable with fooToRenderable

val x: RichFoo = someFoo
x.render()  // Error: Ambiguous - Drawable.render or Renderable.render?

// Disambiguation:
x.view[Drawable].render()    // OK: explicitly choose Drawable
x.view[Renderable].render()  // OK: explicitly choose Renderable
```

**Example: Ambiguity between intrinsic and extension views**

```jo
interface Logger
  def log(msg: String): Unit
end

interface Printer
  def log(msg: String): Unit  // Same member name as Logger
end

class Service
  def log(msg: String): Unit receives IO.stdout = println("[Service] " + msg)
  view Logger  // Intrinsic view
end

def serviceToPrinter(s: Service): Printer = ...

type RichService = view Service as Printer with serviceToPrinter

val x: RichService = new Service()
x.log("hello")  // Error: Ambiguous - Logger.log or Printer.log?
                // Intrinsic and extension views are checked together!

// Disambiguation:
x.view[Logger].log("hello")   // OK: explicitly use intrinsic Logger view
x.view[Printer].log("hello")  // OK: explicitly use extension Printer view
```

### View Accessor

Access specific views using `value.view[ViewType]`:

```jo
type RichPoint = view Point as
  Drawable with pointToDrawable,
  Serializable with pointToSerializable

val p: RichPoint = new Point(3, 4)
val drawable: Drawable = p.view[Drawable]
val serializable: Serializable = p.view[Serializable]

drawable.draw()
serializable.serialize()
```

**View accessor semantics:**

For `value.view[V]` where `value` has type `view T as V1, V2, ...`:

- If `V` is not a view (neither intrinsic nor extension), report error
- If `V` is an intrinsic view of `T`, rewrite to `value.V`
- If `V` is an extension view:
    - If the view adapter is a function `f`, rewrite to `f(value)`
    - If the view adapter is the constructor of class `C`, rewrite to `new C(value)`

### Type Adaptation

When type checking requires adapting `value: T` to expected type `E`, the following adaptation mechanism applies:

1. **View adaptation**: If a view `V` of `T` (either intrinsic or extension) conforms to `E`, return `value.view[V]`
2. **Duck type adaptation**: If `E` is a duck type, try duck type adaptation from `T` to `E`

**Example: View adaptation for view types**

```jo
class Foo
  view Printer  // Intrinsic view
end

def fooToLogger(f: Foo): Logger = ...

type RichFoo = view Foo as Logger with fooToLogger

def takesPrinter(p: Printer): Unit = ...
def takesLogger(l: Logger): Unit = ...

val x: RichFoo = new Foo()
takesPrinter(x)  // OK: View adaptation via intrinsic Printer view
takesLogger(x)   // OK: View adaptation via extension Logger view
```

Both intrinsic views (declared in the class) and extension views (declared in the view type) are considered during view adaptation.

## Restrictions

### View Type Requirements

**Extension views must be interface or class types**, not type aliases, union types, or other view types.

```jo
interface Logger
  def log(msg: String): Unit
end

type LoggerAlias = Logger

class Foo
end

def fooToLogger(f: Foo): Logger = ...

// Valid: interface type
type RichFoo1 = view Foo as Logger with fooToLogger

// Invalid: type alias
type RichFoo2 = view Foo as LoggerAlias with fooToLogger  // Error

// Invalid: union type
type RichFoo3 = view Foo as (Logger | Printer) [with ...]  // Error

// Invalid: another view type
type ViewBar = view Bar as Baz [with ...]
type RichFoo4 = view Foo as ViewBar [with ...]  // Error
```

**Rationale:**

- **Views are contracts** - Contracts should be simple, clear, and directly named
- **Primary use: augmenting behaviors** - Not for complex type conversions
- **Discoverability** - Extension views should be clear from their names

### No Nesting

**The underlying type must not be another view type.**

```jo
type RichFoo = view Foo as Bar with fooToBar
type SuperRichFoo = view RichFoo as Baz with richFooToBaz  // Error: no nesting
```

**Rationale:**

- **Complexity** - Nested views create confusing adapter chains
- **Fear for abuse** - Deeply nested view types are hard to understand
- **Maintainability** - Changes propagate through nesting levels
- **Readability** - Extension specifications in a single place instead of multiple places. No nesting enforces using the same type name, and each new type name incurs cognitive overhead. This encourages reusing the same names.

!!! tip "Recommended approaches"
    Two styles are advocated:

    1. **Multiple locally scoped view types** - Different view types with different views in different contexts for the same base type
    2. **Shared view type with multiple views** - A single view type with multiple views shared across multiple contexts

    Both approaches are better than nested view types.

    ```jo
    // Option 1: Multiple simple view types in different contexts
    type RichFoo = view Foo as Bar with fooToBar
    type OtherRichFoo = view Foo as Baz with fooToBaz

    // Option 2: Single view type with multiple views
    type SuperRichFoo = view Foo as
      Bar with fooToBar,
      Baz with fooToBaz
    ```

### Coherency Check

**Extension views and intrinsic views must be distinct.**

```jo
class Foo
  view Logger  // Intrinsic view
end

def fooToLogger(f: Foo): Logger = ...

// Error: Logger appears as both intrinsic and extension view
type RichFoo = view Foo as Logger with fooToLogger
```

**Rationale:**

- **Ambiguity prevention** - Prevents confusion about which view instance to use
- **Deterministic resolution** - Ensures exactly one way to access each view
- **Clear semantics** - Either a view is intrinsic or it's an extension, never both

**Valid: Different views**

```jo
class Foo
  view Logger  // Intrinsic
end

def fooToPrinter(f: Foo): Printer = ...

// OK: Printer is not an intrinsic view
type RichFoo = view Foo as Printer with fooToPrinter
```

## Validation Rules

### Adapter Validation

**For each view adapter `f` in `view T as V [with f]`:**

1. **Function signature**:

     - Must be a named function (not lambda)
     - Exactly one regular parameter of type `T` (or supertype)
     - Return type must be `V` (or subtype)
     - No type parameters
     - No auto parameters
     - Context parameters allowed

2. **Constructor validation (when adapter omitted)**:

     - `V` must be a class type
     - `V` must have a constructor accepting `T`

**Examples:**

```jo
class Foo

interface Bar

// Valid adapters
def fooToBar(f: Foo): Bar = ...
def fooToBarWithContext(f: Foo): Bar receives logger = ...

// Invalid adapters
def badAdapter1(f: Foo, x: Int): Bar = ...      // Multiple parameters
def badAdapter2[T](f: T): Bar = ...             // Type parameter
def badAdapter3(auto f: Foo => Bar): Bar = ...  // Auto parameter
val badAdapter4 = (f: Foo) => new Bar()         // Not a named function

// Constructor as adapter
class Wrapper(foo: Foo)
end

type WrappedFoo = view Foo as Wrapper  // OK: Wrapper(Foo) exists
```

### Coherency Validation

**At view type definition time, verify:**

1. **No duplicate extension views** - Each view type appears at most once in the extension list
2. **No overlap with intrinsic views** - Extension views must not match any intrinsic views of underlying type

```jo
interface Logger

interface Printer

class Foo
  view Logger  // Intrinsic view
end

def fooToLogger(f: Foo): Logger = ...
def fooToPrinter1(f: Foo): Printer = ...
def fooToPrinter2(f: Foo): Printer = ...

// Error: Duplicate extension view
type BadRichFoo1 = view Foo as
  Printer with fooToPrinter1,
  Printer with fooToPrinter2  // Error: Printer appears twice

// Error: Overlap with intrinsic view
type BadRichFoo2 = view Foo as Logger with fooToLogger  // Error: Foo already has Logger

// OK: No overlap
type GoodRichFoo = view Foo as Printer with fooToPrinter1
```

### View Type Restrictions

View types in the extension list must be **interface or class types**:

```jo
interface Logger

type LoggerAlias = Logger

class Foo

// Error: Type alias not allowed
type RichFoo = view Foo as LoggerAlias [with ...]  // Error
```

## Examples

### Basic Extension

```jo
// Library type you can't modify
class Point(x: Int, y: Int)
  def distance(): Float = sqrt(x * x + y * y)
end

// Define interface and adapter class
interface Drawable
  def draw(): Unit
end

class PointDrawable(p: Point)
  def draw(): Unit = drawCircle(p.x, p.y)
  view Drawable
end

def pointToDrawable(p: Point): Drawable = new PointDrawable(p)

// Create extended type
type DrawablePoint = view Point as Drawable with pointToDrawable

def render(d: DrawablePoint): Unit =
  d.draw()      // Via Drawable view
  d.distance()  // Direct member from Point

val p = new Point(3, 4)
render(p)  // Automatic adaptation from Point to DrawablePoint
```

### Multiple Extensions

```jo
interface Comparable
  def compare(other: Comparable): Int
end

interface Serializable
  def serialize(): String
end

interface Cloneable
  def clone(): Data
end

class Data(value: Int)

def dataToComparable(d: Data): Comparable = ...
def dataToSerializable(d: Data): Serializable = ...
def dataToCloneable(d: Data): Cloneable = ...

type RichData = view Data as
  Comparable with dataToComparable,
  Serializable with dataToSerializable,
  Cloneable with dataToCloneable

def process(d: RichData): Unit =
  d.compare(other)   // Via Comparable view
  d.serialize()      // Via Serializable view
  d.clone()          // Via Cloneable view
  // All accessible through member selection
```

### Extension with Intrinsic Views

```jo
// Type with intrinsic view
class Employee(name: String, id: Int)
  def getName(): String = name

  def log(msg: String): Unit = println("[Employee " + id + "] " + msg)
  view Logger
end

// Add more capabilities via extension
interface Notifiable
  def notify(msg: String): Unit
end

def employeeToNotifiable(e: Employee): Notifiable = ...

type RichEmployee = view Employee as Notifiable with employeeToNotifiable

val emp: RichEmployee = new Employee("Alice", 123)

// Access direct members
emp.getName()  // Direct member

// Access intrinsic view
emp.log("working")  // Via intrinsic Logger view

// Access extension view
emp.notify("meeting")  // Via extension Notifiable view
```

### Disambiguation

```jo
interface Renderer
  def render(): String
end

interface Formatter
  def render(): String
end

class Document(content: String)

def docToRenderer(d: Document): Renderer = ...
def docToFormatter(d: Document): Formatter = ...

type RichDocument = view Document as
  Renderer with docToRenderer,
  Formatter with docToFormatter

val doc: RichDocument = new Document("Hello")

// Ambiguous: both views have render()
doc.render()  // Error: Ambiguous

// Disambiguate with view accessor
doc.view[Renderer].render()   // OK: uses Renderer
doc.view[Formatter].render()  // OK: uses Formatter
```

### Constructor as Adapter

```jo
class Point(x: Int, y: Int)

class Wrapper(p: Point)
  def unwrap(): Point = p
  def description(): String = "Wrapped point"
end

// No adapter needed - uses constructor
type WrappedPoint = view Point as Wrapper

val p: WrappedPoint = new Point(3, 4)
p.description()  // Via Wrapper view
p.unwrap()       // Via Wrapper view
```

## Extension Views vs Duck Types

**Duck types** and **view types** serve complementary roles:

| Feature | Duck Types | View Types |
|---------|-----------|------------|
| **Purpose** | Flexible parameter acceptance | Type extension |
| **Direction** | Many types → one target type | One type → many capabilities |
| **Use case** | Function parameters | Adding behaviors |
| **Naming** | `Printable`, `StringLike` | `RichFoo`, `ExtendedBar` |

**Duck types** - "What can become X?"
```jo
type Printable = like String with [intToStr, .toString]
def println(s: Printable): Unit = ...
println(42)  // Adapted via intToStr
```

**View types** - "X enriched with Y"
```jo
type RichInt = view Int as Comparable with intComparator
def compare(x: RichInt, y: RichInt): Int = x.compare(y)
```

Use duck types for flexible input, view types for extended capabilities.
