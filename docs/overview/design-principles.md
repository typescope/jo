# Design Principles

This document outlines the core design principles that guide Jo's language design decisions.

## 1. Local Reasoning

**Principle:** The behavior of code should be understandable by examining the code locally, without needing to search through distant parts of the codebase.

**Key Requirement:** Implicits must strictly abide by local reasoning—no global or lexical scope search.

### Why This Matters

Many languages with implicit features (Scala implicits, Haskell type classes, Swift extensions, Rust traits) require searching through imports and lexical scope to determine what methods are available on a value. This breaks local reasoning:

```scala
// Scala example - hard to understand without checking imports
val point = Point(3, 4)
point.draw()  // Where does draw() come from? Must check imports!
```

The same expression `point.draw()` may succeed or fail depending on what's imported in the current module. This makes code hard to understand, maintain, and refactor.

### Jo's Approach

**Auto parameters** are local—resolved from the calling context, not searched globally:

```jo
def process(auto logger: Logger): Unit =
  logger.log("processing")

// At call site, must explicitly provide or propagate
process() with logger = myLogger
```

**View types** make extensions explicit in type declarations:

```jo
type RichPoint = view Point as
  Drawable with pointToDrawable,
  Serializable with pointToSerializable

// Looking at RichPoint's definition tells you exactly what it can do
```

**Duck types** declare adapters in the type itself:

```jo
type Printable = like String with [intToStr, .toString]

// The type declaration shows exactly which conversions are allowed
```

In all cases, looking at the type declaration or function signature tells you what's available—no scope searching required.

## 2. Freedom with Checks

**Principle:** Users should have powerful features, but the language should provide checks to prevent misuse.

### Power with Safety

Jo provides powerful abstraction mechanisms but includes checks to ensure they're used correctly:

**View types** enable flexible type extension, but with coherency checks:

```jo
class Foo
  view Logger  // Intrinsic view
end

// Error: Can't add Logger as extension view when it's already intrinsic
type RichFoo = view Foo as Logger with fooToLogger  // Coherency check fails
```

**Context parameters** provide flexible dependency injection, but can be made disciplined:

```jo
param logger: Logger

// Context requirements are visible in function signatures
def process(data: Data): Result receives logger = ...

// Type system ensures all required context is provided
```

**Duck types** allow flexible parameter acceptance, but with shadowing checks:

```jo
def intToStr(x: Int): String = ...
def anotherIntToStr(x: Int): String = ...

// Error: anotherIntToStr shadowed by intToStr
type StringLike = like String with [intToStr, anotherIntToStr]
```

### Philosophy

Rather than restricting features to prevent misuse, Jo gives users powerful tools and includes targeted checks to catch errors. This preserves expressiveness while maintaining safety.

## 3. Explicitness over Implicitness

**Principle:** The compiler should not perform complex guessing. Users should make their intent clear when it's not obvious.

### Type Inference Restrictions

Type inference is intentionally restricted:

```jo
// Function return types must be declared
def compute(x: Int): Int = x * 2  // ✓ Return type explicit

// Variable types can be inferred from RHS
val result = compute(5)  // ✓ Type inferred from compute's return type

// But complex inference is avoided
def process(x) = x * 2  // ✗ Parameter type must be explicit
```

**Rationale:** Explicit types serve as documentation and prevent accidental type changes from propagating through the codebase.

### No Implicit Conversions

View adaptation and duck type adaptation must be explicit in the type:

```jo
type RichInt = view Int as Comparable with intComparator

def takeComparable(c: Comparable): Unit = ...

val x: RichInt = 42
takeComparable(x)  // ✓ Automatic, but declared in RichInt's definition

// NOT like Scala where imports add invisible conversions
```

### Clear Intent

When there are multiple valid approaches, users must specify their choice:

```jo
interface Drawable
  def render(): Unit
end

interface Renderable
  def render(): Unit
end

type Rich = view Foo as Drawable with toDrawable, Renderable with toRenderable

val x: Rich = someFoo
x.render()  // Error: Ambiguous - which render()?
x.view[Drawable].render()  // ✓ Intent is clear
```

## 4. Name Discipline

**Principle:** Naming should follow strict, predictable rules.

### No Overloading

Jo does not support method overloading. Each name refers to exactly one definition:

```jo
// NOT allowed in Jo
def process(x: Int): Result = ...
def process(x: String): Result = ...  // Error: duplicate name

// Instead, use different names or generic types
def processInt(x: Int): Result = ...
def processString(x: String): Result = ...
```

**Rationale:**

- **Simplicity:** No complex overload resolution rules
- **Predictability:** A name always refers to one thing
- **Tooling:** Easier for IDEs and refactoring tools
- **Clarity:** Different operations deserve different names

### Strict Scoping Rules

Names follow clear, hierarchical scoping rules without special cases:

```jo
val x = 1

def foo(): Unit =
  val x = 2  // Shadows outer x
  println(x)  // Prints 2

foo()
println(x)  // Prints 1
```

No implicit resolution, no special lookup rules, no scope pollution from imports.

### Consistency

The same naming discipline applies everywhere:
- Type names
- Function names
- Variable names
- Parameter names
- Module names

No special cases, no exceptions.

## 5. Semantic Lucidity

**Principle:** Semantics must be clear and cross-platform.

### Platform Independence

Jo's semantics are defined independently of any particular platform:

```jo
// Interface values work the same on all backends
val iter: Iterator[Int] = range.Iterator

// No platform-specific behavior differences
```

The JavaScript, native stack machine, and native register machine backends may use different representations internally (e.g., for interface values), but the observable behavior is identical.

### Clear Semantics

Language features have well-defined, unambiguous semantics:

**View adaptation** has clear rewriting rules:

```jo
// value.view[V] rewrites to:
// - value.V (if V is intrinsic view)
// - f(value) (if V is extension view with adapter f)
// - new C(value) (if V is extension view with constructor C)
```

**Member resolution** follows a clear priority:

```jo
// For value.member:
// 1. Direct members in the type
// 2. All views (intrinsic and extension together)
```

**Type adaptation** is well-ordered:

```jo
// To adapt value: T to E:
// 1. Direct conformance (T <: E)
// 2. View adaptation (view V of T conforms to E)
// 3. Duck type adaptation
```

### No Undefined Behavior

Jo avoids undefined behavior:
- All operations have defined semantics
- Type safety prevents undefined operations
- Runtime errors are well-specified

This makes Jo programs predictable and portable across implementations.

## Summary

These design principles work together to create a language that is:

- **Understandable:** Local reasoning makes code easy to comprehend
- **Safe:** Checks prevent misuse of powerful features
- **Explicit:** Intent is clear, no hidden magic
- **Simple:** Consistent naming discipline reduces complexity
- **Predictable:** Clear semantics across all platforms

Jo prioritizes long-term maintainability and clarity over short-term convenience. Features that require global reasoning, implicit behavior, or platform-specific semantics are avoided or carefully constrained.
