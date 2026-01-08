# Algebraic Data Types

Jo provides algebraic data types (ADTs) through the `union` keyword, enabling the definition of types that represent a choice between multiple alternatives (sum types).

## Syntax

```
union_def = "union" ident [tparams] "=" branch {"|" branch}
branch = ident [param_section]
```

**Basic form:**
```jo
union Color = Red | Green | Blue
```

**With type parameters:**
```jo
union Either[T, U] = Left(x: T) | Right(y: U)
```

**With associated data:**
```jo
union Result = Ok(value: Int) | Err(message: String)
```

## Desugaring

ADTs are desugared into simpler language constructs: a union type alias and separate definitions for each branch.

### Example: Simple Enum

**Source:**
```jo
union Color = Red | Green | Blue
```

**Desugars to:**
```jo
type Color = Red | Green | Blue

object Red
object Green
object Blue

// object Red further desugars to
def Red: Red = ...
pattern Red: Red = case _
class Red
```

**Resulting components:**

- `Color` is a union type alias
- `Red`, `Green`, `Blue` are classes (with no fields)
- Constructor functions for creating instances
- Patterns for matching

### Example: ADT with Associated Data

**Source:**
```jo
union Option[T] = Some(value: T) | None
```

**Desugars to:**
```jo
type Option[T] = Some[T] | None

class Some[T](value: T)
def Some[T](value: T): Some[T] = new Some[T](value)
pattern Some[T](value: T): Some[T] = case o then value = o.value

object None

// object None further desugars to
def None: None = ...
pattern None: None = case _
class None
```

**Key points:**

- Each branch becomes a separate class
- Parameterless branches desugar to an object
- Constructor function for creating instances
- Pattern for destructuring in match expressions
- Type parameters are automatically inferred based on usage

## Type Parameters

When a union has type parameters, the compiler automatically determines which type parameters each branch needs by analyzing the parameter types.

**Automatic type parameter inference:**

Branches only receive the type parameters they actually use:

```jo
union Option[T] = Some(value: T) | None
```

**Desugars to:**
```jo
type Option[T] = Some[T] | None

class Some[T](value: T)  // Has T because it uses T
object None              // No type parameters - doesn't use T
// + constructors and patterns
```

**Another example:**

```jo
union Either[A, B] = Left(value: A) | Right(value: B)
```

**Desugars to:**
```jo
type Either[A, B] = Left[A] | Right[B]

class Left[A](value: A)    // Only has A - doesn't use B
class Right[B](value: B)   // Only has B - doesn't use A
// + constructors and patterns
```

Each branch gets only the type parameters it references in its constructor parameters. The union type definition combines all branches with their respective type parameters.

!!! note "Why Automatic Inference?"
    This design enables flexible union composition. Since branches only get the type parameters they use, they can be easily reused in different union types with different type parameter lists.

    For example, `None` (no type parameters) can be freely reused in any union, and `Left[A]` can be reused in a union with a single type parameter `E` instead of being locked to `[A, B]`.

    If all branches blindly inherited all type parameters from their parent union, this flexibility would be lost. See [Flexible Unions](#flexible-unions) for examples.

## Pattern Matching

ADTs are deconstructed using pattern matching, which provides exhaustive checking and type-safe access to associated data.

**Example:**
```jo
def describe(opt: Option[Int]): String =
  match opt
    case Some(v) => "Value: " + v
    case None => "No value"
  end
```

**Exhaustiveness checking:**

The compiler ensures all branches are covered:

```jo
def process(result: Result): Int =
  match result
    case Ok(v) => v
    // Error: Missing case for Err
  end
```

**Pattern matching uses the generated patterns:**

- `Some(v)` matches using the `Some` pattern, binding the `value` field to `v`
- `None` matches using the `None` pattern (no bindings)

## Examples

### Option Type

Represents an optional value that may or may not be present:

```jo
union Option[T] = Some(value: T) | None

def map[T, U](opt: Option[T], f: T => U): Option[U] =
  match opt
    case Some(v) => Some(f(v))
    case None => None
  end

def getOrElse[T](opt: Option[T], default: T): T =
  match opt
    case Some(v) => v
    case None => default
  end

// Usage
val x: Option[Int] = Some(42)
val y: Option[Int] = None

val result = map(x, n => n * 2)  // Some(84)
val value = getOrElse(y, 0)       // 0
```

### Result Type

Represents a computation that can succeed or fail:

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)

def divide(x: Int, y: Int): Result[Int, String] =
  if y == 0 then
    Err("Division by zero")
  else
    Ok(x / y)

def main =
  val result = divide(10, 2)
  match result
    case Ok(v) => println("Result: " + v)
    case Err(msg) => println("Error: " + msg)
  end
```

### Binary Tree

Represents a recursive tree structure:

```jo
union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])

def size[T](tree: Tree[T]): Int =
  match tree
    case Leaf(_) => 1
    case Branch(l, r) => size(l) + size(r)
  end

def map[T, U](tree: Tree[T], f: T => U): Tree[U] =
  match tree
    case Leaf(v) => Leaf(f(v))
    case Branch(l, r) => Branch(map(l, f), map(r, f))
  end

// Usage
val tree = Branch(
  Leaf(1),
  Branch(Leaf(2), Leaf(3))
)

val doubled = map(tree, x => x * 2)
val count = size(tree)  // 3
```

### Expression Tree

Represents mathematical expressions:

```jo
union Expr =
  | Const(value: Int)
  | Var(name: String)
  | Add(left: Expr, right: Expr)
  | Mul(left: Expr, right: Expr)

def eval(expr: Expr, env: Map[String, Int]): Int =
  match expr
    case Const(n) => n
    case Var(name) => Map.get(env, name).getOrElse(0)
    case Add(l, r) => eval(l, env) + eval(r, env)
    case Mul(l, r) => eval(l, env) * eval(r, env)
  end

def show(expr: Expr): String =
  match expr
    case Const(n) => "" + n
    case Var(name) => name
    case Add(l, r) => "(" + show(l) + " + " + show(r) + ")"
    case Mul(l, r) => "(" + show(l) + " * " + show(r) + ")"
  end

// Usage
val expr = Add(Const(5), Mul(Var("x"), Const(3)))
val env = Map.from([("x", 4)])

println(show(expr))        // "(5 + (x * 3))"
println(eval(expr, env))   // 17
```

### Linked List

Custom list implementation:

```jo
union List[T] = Nil | Cons(head: T, tail: List[T])

def length[T](list: List[T]): Int =
  match list
    case Nil => 0
    case Cons(_, tail) => 1 + length(tail)
  end

def append[T](xs: List[T], ys: List[T]): List[T] =
  match xs
    case Nil => ys
    case Cons(h, t) => Cons(h, append(t, ys))
  end

def map[T, U](list: List[T], f: T => U): List[U] =
  match list
    case Nil => Nil
    case Cons(h, t) => Cons(f(h), map(t, f))
  end

// Usage
val nums = Cons(1, Cons(2, Cons(3, Nil)))
val doubled = map(nums, x => x * 2)
val len = length(nums)  // 3
```

### JSON Value

Representing JSON data:

```jo
union Json =
  | JNull
  | JBool(value: Bool)
  | JNumber(value: Float)
  | JString(value: String)
  | JArray(elements: Array[Json])
  | JObject(fields: Map[String, Json])

def stringify(json: Json): String =
  match json
    case JNull => "null"
    case JBool(b) => if b then "true" else "false"
    case JNumber(n) => floatToString(n)
    case JString(s) => "\"" + s + "\""
    case JArray(arr) =>
      "[" + Array.map(arr, stringify).join(", ") + "]"
    case JObject(obj) =>
      val pairs = Map.toList(obj).map((k, v) =>
        "\"" + k + "\": " + stringify(v)
      )
      "{" + pairs.join(", ") + "}"
  end
```

## Working with ADTs

### Creating Values

Use the generated constructor functions:

```jo
val none: Option[Int] = None
val some: Option[Int] = Some(42)
val ok: Result[Int, String] = Ok(100)
val err: Result[Int, String] = Err("failed")
```

### Pattern Matching

The primary way to work with ADTs:

```jo
def unwrap[T](opt: Option[T], msg: String): T =
  match opt
    case Some(v) => v
    case None => abort(msg)
  end
```

### Recursive Functions

ADTs work naturally with recursion:

```jo
def sum(list: List[Int]): Int =
  match list
    case Nil => 0
    case Cons(h, t) => h + sum(t)
  end
```

## Flexible Unions

Since union branches desugar to regular classes, unions are inherently flexible and composable.

### Extending Unions

You can extend an existing union by defining a new union type that includes the original union plus additional branches:

```jo
// Original union
union Result[T, E] = Ok(value: T) | Err(error: E)

// Extended union with additional branch
class Pending
type ExtendedResult[T, E] = Result[T, E] | Pending
```

The extended union includes all branches from `Result[T, E]` (which expands to `Ok[T] | Err[E]`) plus the new `Pending` branch.

### Cross-cutting Unions

Since branches are just classes, the same class can appear in multiple union types with overlapping but different sets of branches. This is useful for cross-cutting concerns:

```jo
// UI events - define as individual classes
class Click(x: Int, y: Int)
class KeyPress(key: Char)
class Scroll(delta: Int)

// Mouse-based interactions share Click and Scroll, but not KeyPress
type MouseEvent = Click | Scroll

// Keyboard interactions use KeyPress, but also Click for focus events
type KeyboardEvent = Click | KeyPress

// All interactive events
type UIEvent = Click | KeyPress | Scroll

// Event handlers can be specific
def handleMouse(e: MouseEvent): Unit =
  match e
    case Click(x, y) => println("Clicked at " + x)
    case Scroll(delta) => println("Scrolled " + delta)
  end

def handleKeyboard(e: KeyboardEvent): Unit =
  match e
    case Click(x, y) => println("Focus changed")
    case KeyPress(key) => println("Key pressed: " + key)
  end
```

Here `Click`, `KeyPress`, and `Scroll` are reused across different event type unions that have overlapping but distinct sets of branches, not just subset/superset relationships.

### Behavioral Unions

Union branches can implement interface views to provide shared behavior. View types can then expose the common interface on the union type:

```jo
// Define a common interface
interface Drawable
  def draw(): String
  def area(): Int
end

// Each shape implements the Drawable interface view
class Circle(radius: Int)
  def draw(): String = "Circle(r=" + radius + ")"
  def area(): Int = 3 * radius * radius  // Approximation
  view Drawable
end

class Rectangle(width: Int, height: Int)
  def draw(): String = "Rectangle(" + width + "x" + height + ")"
  def area(): Int = width * height
  view Drawable
end

class Triangle(base: Int, height: Int)
  def draw(): String = "Triangle(b=" + base + ",h=" + height + ")"
  def area(): Int = (base * height) / 2
  view Drawable
end

// Base union type
type ShapeUnion = Circle | Rectangle | Triangle

// Adapter delegates to each branch's Drawable view
def shapeToDrawable(s: ShapeUnion): Drawable =
  match s
    case c: Circle => c.Drawable
    case r: Rectangle => r.Drawable
    case t: Triangle => t.Drawable
  end

// View type exposes Drawable interface on the union
type Shape = view ShapeUnion as Drawable with shapeToDrawable

// Uniform rendering through the view
def renderShape(s: Shape): Unit =
  println(s.draw())
  println("Area: " + s.area())

// Pattern matching for shape-specific behavior
def describe(s: Shape): String =
  match s
    case Circle(r) => "a circle"
    case Rectangle(w, h) => "a rectangle"
    case Triangle(b, h) => "a triangle"
  end

// Usage - combine both approaches
val circle: Shape = Circle(5)
val rect: Shape = Rectangle(4, 6)

renderShape(circle)  // Uses Drawable view (polymorphic)
renderShape(rect)    // Uses Drawable view (polymorphic)

println(describe(circle))  // Uses pattern matching (structural)
```

This demonstrates how view types bridge union types and interfaces: the view type `Shape` provides uniform access to the `Drawable` interface (via the adapter that pattern-matches to delegate to each branch's view) while still supporting exhaustive pattern matching on the underlying union type.

## Design Rationale

### Desugaring to Classes

Each ADT branch becomes a class to leverage Jo's existing type system:

- Pattern matching uses class identity
- No special runtime representation needed
- Composable with other language features

### Direct Access vs Qualified

By default, branches are directly accessible (not qualified):

```jo
union Color = Red | Green | Blue

val c = Red  // Direct access
```

**Qualified access with sections:**

To achieve qualified access like `Color.Red`, define the union inside a section:

```jo
section Color
  union Color = Red | Green | Blue
end

// Usage
val c = Color.Red
```

Or create a type alias if you want both:

```jo
section Color
  union Repr = Red | Green | Blue
end

type Color = Color.Repr

// Usage
val c1 = Color.Red    // Qualified
val c2: Color = c1    // Using type alias
```

**Rationale for default direct access:**

- Concise, especially in pattern matching
- Traditional ML-family convention
- Works well for the functional style Jo encourages
- Sections provide qualified access when needed
- Name collisions can be managed with imports/namespaces

For pattern matching details, see [Pattern Language](pattern-language.md).
