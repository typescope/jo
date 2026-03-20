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

::: info Design Rationale

Invariance is simpler and always type-safe. Variance annotations on type parameters would add significant complexity to type checking without a big improvement in expressiveness and usability.
:::
### Interface Type Equality

::: info Restriction: Interface Type Equality Not Supported
Jo does not support equality for interface types. Similar to how function equality is not supported in many FP languages, interface types do not have equality defined:

```jo
val r = new Range(0, 10)
val iter1: Iterator[Int] = r.Iterator
val iter2: Iterator[Int] = r.Iterator

iter1 == iter2  // Error: equality not defined for interface types
```

This applies to all interface-typed values, regardless of how they were obtained (type adaptation or direct interface-typed expressions).
:::
### Concrete Method Implementation

Interfaces can provide concrete implementations for methods:

```jo
interface Eq[T]
  def equal(x: T, y: T): Bool                      // Abstract method
  def notEqual(x: T, y: T): Bool = !equal(x, y)    // Concrete method
end

class Counter(count: Int)
  // Only need to implement equal, notEqual is provided by interface
  def equal(c1: Counter, c2: Counter): Bool = c1.count == c2.count

  view Eq[Counter]
end
```

#### Concrete Methods Are Final

Concrete methods (methods with implementations in interfaces) **cannot be overridden** by implementing classes. Furthermore, classes with direct views **cannot have members (methods or fields) that conflict** with concrete interface methods:

```jo
class BadCounter1(count: Int)
  def equal(c1: BadCounter1, c2: BadCounter1): Bool = c1.count == c2.count

  // ERROR: Cannot override concrete method
  def notEqual(c1: BadCounter1, c2: BadCounter1): Bool = c1.count != c2.count

  view Eq[BadCounter1]
end

class BadCounter2(count: Int, notEqual: Bool)  // ERROR: Field conflicts with concrete method
  def equal(c1: BadCounter2, c2: BadCounter2): Bool = c1.count == c2.count

  view Eq[BadCounter2]
end
```

This restriction ensures consistent behavior when objects are used via their interface type:

```jo
def testEquality(eq: Eq[Counter], c1: Counter, c2: Counter): Unit =
  val same = eq.equal(c1, c2)
  val diff = eq.notEqual(c1, c2)  // Always calls interface's concrete implementation
  // ...
end

val counter = new Counter(42)
testEquality(counter, counter, counter)  // Works correctly due to subtyping
```

::: info Design Rationale

**Concrete methods are final for predictability**: When you call a concrete method from an interface, you know exactly which implementation executes—the one defined in the interface. This is crucial with direct view subtyping, where objects are used as interface types. The restriction prevents:

- **Behavioral inconsistency**: Different behavior depending on whether you access via class type `C` or interface type `I`
- **Violation of LSP**: Subtypes behaving unexpectedly when substituted for the interface
- **Field/method confusion**: A field in the class shadowing a method in the interface

Abstract methods MUST be implemented (they're requirements), but concrete methods MUST NOT be shadowed (they're guarantees).
:::
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

- [Classes and Views](../concepts/interface-views.md) - High-level design
- [Lambda Types](../types/lambda-types.md) - Lambda interface adaptation
- [Class Definitions](class-definitions.md) - Implementing interfaces in classes
