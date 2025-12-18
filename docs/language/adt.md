# Algebraic Data Types

Jo provides algebraic data types (ADTs) through the `union` keyword, enabling the definition of types that represent a choice between multiple alternatives (sum types).

## Overview

An algebraic data type defines a type as a union of distinct branches, where each branch can carry associated data. ADTs are ideal for representing domain concepts that have multiple variants, such as optional values, results, trees, or abstract syntax.

**Key characteristics:**

- Multiple named branches (variants)
- Each branch can have associated data
- Exhaustive pattern matching
- Type-safe discrimination between branches

## Syntax

```
union_def = "union" ident [tparams] "=" branch {"|" branch}
branch = ident [param_section]
```

**Basic form:**
```jo
union Name = Branch1 | Branch2 | Branch3
```

**With type parameters:**
```jo
union Name[T, U] = Branch1(x: T) | Branch2(y: U)
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

class Red
def Red: Red = new Red
pattern Red: Red = case _

class Green
def Green: Green = new Green
pattern Green: Green = case _

class Blue
def Blue: Blue = new Blue
pattern Blue: Blue = case _
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

class None
def None: None = new None
pattern None: None = case _
```

**Key points:**

- Each branch becomes a separate class
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
class None                // No type parameters - doesn't use T
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
    case Some(v) => "Value: " + intToStr(v)
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
    case Ok(v) => println("Result: " + intToStr(v))
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
    case Const(n) => intToStr(n)
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

### Nested Matching

Pattern matching can be nested:

```jo
def flatMap[T, U](opt: Option[T], f: T => Option[U]): Option[U] =
  match opt
    case Some(v) =>
      match f(v)
        case Some(u) => Some(u)
        case None => None
      end
    case None => None
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

### Reusing Branches Across Unions

Since branches are just classes, the same class can appear in multiple union types. To avoid duplicate class definitions, use `type` for the second union:

```jo
union Option[T] = Some(value: T) | None  // Creates Some[T] and None classes

// Reuse Some[T] in a different union type - use 'type' to avoid redefining Some
class Null
type Nullable[T] = Some[T] | Null

// Some[T] is shared between both unions
val opt: Option[Int] = Some(42)
val nullable: Nullable[Int] = Some(42)  // Same class, different union type
```

Branches with different type parameter needs can also be reused:

```jo
union Either[A, B] = Left(value: A) | Right(value: B)  // Creates Left[A] and Right[B]

// Reuse Left[A] in a different union - use 'type' to avoid redefining Left
class Pending
class Done
type Status[E] = Pending | Left[E] | Done
```

This enables flexible type composition without duplicating class definitions.

### Creating Open Unions

You can create "open" unions by defining the union type separately from the branches:

```jo
// Define branches as standalone classes
class Success(value: Int)
class Failure(error: String)
class Pending

// Create union type from existing classes
type Status = Success | Failure | Pending

// Later, extend with a new union
class Cancelled
type ExtendedStatus = Success | Failure | Pending | Cancelled
```

**Benefits:**

- Maximum flexibility in composing types
- Avoid code duplication
- Mix and match branches across different contexts
- Create specialized unions from a common set of classes

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
