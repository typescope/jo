# Algebraic Data Types

An ADT represents a value that is exactly one of several named alternatives. It is the
standard tool for modelling data whose shape varies by case: optional values, success vs.
failure, recursive structures, and more.

For the formal specification, see [Algebraic Data Types](../language/definitions/union-definition.md).

## Basic Usage

### Simple Enum

The simplest form — branches with no data:

```jo
union Direction = North | South | East | West

def describe(d: Direction): String =
  match d
    case North => "up"
    case South => "down"
    case East  => "right"
    case West  => "left"
  end
```

### Branches with Data

Branches can carry associated values. The compiler checks that every case is handled:

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)

def divide(x: Int, y: Int): Result[Int, String] =
  if y == 0 then Err("Division by zero")
  else Ok(x / y)

match divide(10, 2)
  case Ok(v)    => println("Result: " + v)   // 5
  case Err(msg) => println("Error: " + msg)
end
```

Omitting a branch is a compile-time error.

## Methods

Methods defined in the `union` body operate on the union value via `this`:

```jo
union Option[T] = Some(value: T) | None
  def map[U](f: T => U): Option[U] =
    match this
      case Some(v) => Some(f(v))
      case None    => None
    end

  def getOrElse(default: T): T =
    match this
      case Some(v) => v
      case None    => default
    end
end

val x: Option[Int] = Some(42)
val y: Option[Int] = None

x.map(n => n * 2)    // Some(84)
y.getOrElse(0)        // 0
```

## Recursive Types

A union branch may reference the union type itself, enabling recursive structures:

```jo
union Tree[T] = Leaf(value: T) | Branch(left: Tree[T], right: Tree[T])
  def size: Int =
    match this
      case Leaf(_)      => 1
      case Branch(l, r) => l.size + r.size
    end

  def map[U](f: T => U): Tree[U] =
    match this
      case Leaf(v)      => Leaf(f(v))
      case Branch(l, r) => Branch(l.map(f), r.map(f))
    end
end

val tree = Branch(Leaf(1), Branch(Leaf(2), Leaf(3)))
tree.size              // 3
tree.map(n => n * 2)  // Branch(Leaf(2), Branch(Leaf(4), Leaf(6)))
```

A more complex example: an expression tree with an evaluator and printer defined as
separate functions:

```jo
union Expr =
  | Const(value: Int)
  | Var(name: String)
  | Add(left: Expr, right: Expr)
  | Mul(left: Expr, right: Expr)

def eval(expr: Expr, env: Map[String, Int]): Int =
  match expr
    case Const(n)    => n
    case Var(name)   => Map.get(env, name).getOrElse(0)
    case Add(l, r)   => eval(l, env) + eval(r, env)
    case Mul(l, r)   => eval(l, env) * eval(r, env)
  end

def show(expr: Expr): String =
  match expr
    case Const(n)    => "" + n
    case Var(name)   => name
    case Add(l, r)   => "(" + show(l) + " + " + show(r) + ")"
    case Mul(l, r)   => "(" + show(l) + " * " + show(r) + ")"
  end

val expr = Add(Const(5), Mul(Var("x"), Const(3)))
show(expr)                              // "(5 + (x * 3))"
eval(expr, Map.from([("x", 4)]))        // 17
```

## Composing Unions

Because union branches desugar to ordinary classes, unions are composable in two ways.

### Extending a Union

Add branches by wrapping an existing union in a new type:

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)

class Pending
type AsyncResult[T, E] = Result[T, E] | Pending
// expands to: Ok[T] | Err[E] | Pending
```

### Cross-Cutting Unions

The same class can belong to multiple independent union types:

```jo
class Click(x: Int, y: Int)
class KeyPress(key: Char)
class Scroll(delta: Int)

type MouseEvent    = Click | Scroll
type KeyboardEvent = Click | KeyPress
type UIEvent       = Click | KeyPress | Scroll

def handleMouse(e: MouseEvent): Unit =
  match e
    case Click(x, y)  => println("Clicked at " + x + ", " + y)
    case Scroll(delta) => println("Scrolled " + delta)
  end

def handleKeyboard(e: KeyboardEvent): Unit =
  match e
    case Click(x, y)  => println("Focus at " + x + ", " + y)
    case KeyPress(key) => println("Key: " + key)
  end
```

Each handler is exhaustive over its own type. `UIEvent` can be dispatched to both.

## Qualified Access

By default, branches are visible directly in the enclosing scope:

```jo
union Color = Red | Green | Blue
val c = Red
```

To require qualified access (`Color.Red`), define the union inside a section:

```jo
section Color
  union Color = Red | Green | Blue
end

val c = Color.Red
```

For both qualified access and a standalone type name, use a type alias:

```jo
section Color
  union Repr = Red | Green | Blue
end

type Color = Color.Repr

val c1 = Color.Red    // qualified
val c2: Color = c1    // type alias
```
