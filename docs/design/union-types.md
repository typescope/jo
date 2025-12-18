# Union Types

## Overview

Union types enable a value to be one of several possible class types, with the ability to distinguish between alternatives at runtime through pattern matching.

Unlike traditional sum types or tagged unions found in functional languages, Jo's union types are based on **class identity** rather than explicit tags or constructors.

## Motivation

Union types solve the problem of representing values that can be one of several specific types:

```jo
// Represent a result that can be either success or failure
class Success(value: Int)

class Failure(error: String)

def divide(x: Int, y: Int): Success | Failure =
  if y == 0 then new Failure("Division by zero")
  else new Success(x / y)

def processResult(result: Success | Failure): String =
  result match
    case s: Success => "Result: " + s.value
    case f: Failure => "Error: " + f.error
  end
end
```

## Syntax

### Union Type Syntax

```
union_type = type ("|" type)+
```

Union types are written using the `|` operator between type expressions:

```jo
// Simple union of two class types
type Shape = Circle | Rectangle

// Union of multiple class types
type Result = Success | Warning | Error

// Nested unions (flattened automatically)
type Number = Int | Float
type Value = Number | String  // Equivalent to: Int | Float | String

// Union as return type
def parse(s: String): Int | Error =
  if isValid(s) then parseToInt(s)
  else new Error("Invalid input")

// Union as parameter type
def describe(shape: Circle | Rectangle): String =
  shape match
    case c: Circle => "Circle with radius " + c.r
    case r: Rectangle => "Rectangle " + r.w + "x" + r.h
  end
```

### Type Alias for Unions

Union types can be named using type aliases:

```jo
type Shape = Circle | Rectangle | Triangle

def area(s: Shape): Float =
  s match
    case c: Circle => 3.14 * c.r * c.r
    case r: Rectangle => r.w * r.h
    case t: Triangle => 0.5 * t.base * t.height
  end
```

### Pattern Matching on Unions

Union types are deconstructed using pattern matching with type patterns:

```jo
def processResult(result: Success | Failure): Unit =
  result match
    case s: Success =>
      println("Got value: " + s.value)
    case f: Failure =>
      println("Got error: " + f.error)
  end
end
```

The compiler:

1. Verifies all alternatives are covered (exhaustiveness check)
2. Generates code to test the runtime type against each case
3. Binds the scrutinee to the appropriate type in each branch

## Type Checking

### Well-Formed Union Types

A union type `T1 | T2 | ... | Tn` is well-formed if:

1. **Each branch is a class type or union type**: `Ti` must be:

    - A class type: `C[T1, ..., Tm]` where `C` is a class definition
    - Another union type (which will be flattened)

2. **No type parameters**: Branches cannot contain type parameters:
   ```jo
   // Invalid
   def foo[T](x: T | Int): Unit = ...  // Error: T is a type parameter
   ```

3. **No interface types**: Branches cannot be interface types:
   ```jo
   interface Logger
     def log(msg: String): Unit
   end

   // Invalid
   type LoggerOrInt = Logger | Int  // Error: Logger is an interface type
   ```

4. **All branches statically known**: The complete set of alternatives must be determined at compile time

5. **No duplicate branches**: After normalization, each class type appears at most once:
   ```jo
   // Invalid
   type Foo = Int | String | Int  // Error: Int appears twice
   ```

!!! note "Rationale for Restrictions"
    - **No type parameters**: Type parameters are resolved at instantiation time, making it impossible to know the complete set of alternatives statically
    - **No interface types**: Interface types don't have class identity and can be implemented by any class, making them open-ended
    - **Statically known branches**: Enables exhaustiveness checking and efficient compilation

### Subtyping with Union Types

Union types introduce limited subtyping relationships:

**Each branch is a subtype of the union:**

```jo
class Success(value: Int)
class Failure(error: String)

type Result = Success | Failure

def makeSuccess(): Result = new Success(42)  // Success <: Result
def makeFailure(): Result = new Failure("oops")  // Failure <: Result
```

**Union subsumption:**

```jo
type Small = Int | String
type Large = Int | String | Bool

// Small <: Large (all branches of Small are in Large)

def foo(x: Small): Large = x  // Valid: implicit widening
```

### Type Adaptation and Member Selection

Union types **do not support member selection** directly:

```jo
type Shape = Circle | Rectangle

val s: Shape = new Circle(5)
val r = s.r  // Error: Cannot select member 'r' from union type Shape
```

!!! note "Rationale"
    Different branches may have different members. Use pattern matching to access members:

```jo
val radius = s match
  case c: Circle => c.r
  case r: Rectangle => 0  // Doesn't have radius
end
```

!!! info "No Common Interface Required"
    Unlike sealed interfaces in some languages, union types do not require branches to implement a common interface. Each branch is independent.

### Exhaustiveness Checking

Pattern matching on union types must be exhaustive:

```jo
type Result = Success | Warning | Failure

def process(r: Result): String =
  r match
    case s: Success => "ok"
    case w: Warning => "warning"
    // Error: Missing case for Failure
  end
end
```

The compiler tracks which branches are covered and reports missing cases.

#### Redundant Patterns

```jo
def process(r: Success | Failure): String =
  r match
    case s: Success => "ok"
    case f: Failure => "error"
    case x: Success => "redundant"  // Warning: Redundant case (Success already covered)
  end
end
```

## Examples

### Result Type

A common pattern for operations that can fail:

```jo
class Ok(value: Int)
class Err(message: String)

type Result = Ok | Err

def safeDivide(x: Int, y: Int): Result =
  if y == 0 then new Err("Division by zero")
  else new Ok(x / y)

def main receives IO.stdout =
  val result = safeDivide(10, 2)
  result match
    case ok: Ok =>
      println("Result: " + ok.value)
    case err: Err =>
      println("Error: " + err.message)
  end
end
```

### JSON Value Representation

```jo
class JsonNull
class JsonBool(value: Bool)
class JsonNumber(value: Float)
class JsonString(value: String)
class JsonArray(elements: List[JsonValue])
class JsonObject(fields: Map[String, JsonValue])

type JsonValue = JsonNull | JsonBool | JsonNumber | JsonString | JsonArray | JsonObject

def stringify(json: JsonValue): String =
  json match
    case n: JsonNull => "null"
    case b: JsonBool => if b.value then "true" else "false"
    case num: JsonNumber => floatToString(num.value)
    case s: JsonString => "\"" + s.value + "\""
    case arr: JsonArray =>
      "[" + arr.elements.map(stringify).join(", ") + "]"
    case obj: JsonObject =>
      "{" + obj.fields.map((k, v) => "\"" + k + "\": " + stringify(v)).join(", ") + "}"
  end
end
```

### Expression Tree

```jo
class Const(value: Int)
class Var(name: String)
class Add(left: Expr, right: Expr)
class Mul(left: Expr, right: Expr)

type Expr = Const | Var | Add | Mul

def eval(expr: Expr, env: Map[String, Int]): Int =
  expr match
    case c: Const => c.value
    case v: Var => env.get(v.name).getOrElse(0)
    case a: Add => eval(a.left, env) + eval(a.right, env)
    case m: Mul => eval(m.left, env) * eval(m.right, env)
  end

def main =
  val expr: Expr = new Add(new Const(5), new Mul(new Var("x"), new Const(3)))
  val env = Map.from([("x", 4)])
  val result = eval(expr, env)
  println(result)  // Prints: 17 (5 + 4*3)
end
```

### State Machine

```jo
class Idle
class Running(taskId: Int)
class Paused(taskId: Int, progress: Float)
class Completed(taskId: Int, result: String)

type State = Idle | Running | Paused | Completed

def transition(state: State, event: String): State =
  state match
    case i: Idle =>
      if event == "start" then new Running(1)
      else i

    case r: Running =>
      if event == "pause" then new Paused(r.taskId, 0.5)
      else if event == "complete" then new Completed(r.taskId, "success")
      else r

    case p: Paused =>
      if event == "resume" then new Running(p.taskId)
      else if event == "cancel" then new Idle()
      else p

    case c: Completed =>
      if event == "reset" then new Idle()
      else c
  end
end
```

### Generic Union Types

Union types can contain generic class types:

```jo
class Some[T](value: T)
class None

// Option type using union
type Option[T] = Some[T] | None

def map[A, B](opt: Option[A], f: A -> B): Option[B] =
  opt match
    case s: Some[A] => new Some[B](f(s.value))
    case n: None => n
  end

def getOrElse[T](opt: Option[T], default: T): T =
  opt match
    case s: Some[T] => s.value
    case n: None => default
  end
```

!!! note
    Type parameters in the union type itself are allowed (`Option[T]`), but individual branches cannot be bare type parameters.

## Design Decisions

### Why Restrict to Class Types Only?

Union types only allow class types (not interfaces or type parameters).

!!! warning "Platform Ambiguity: Interfaces Cannot Guarantee Mutual Exclusiveness"
    A value can implement multiple interfaces simultaneously (on both JVM and JavaScript platforms), making it impossible to guarantee that union branches are mutually exclusive.

    For example, with `Logger | Formatter` where both are interfaces, a single object could implement both interfaces. When pattern matching, which branch should it match? The first? The second? This creates fundamental ambiguity that cannot be resolved reliably.

    **This constraint is essential** — allowing interfaces in unions would impose too much constraint on platform implementations of interfaces. Each platform (JVM, JavaScript, native) handles interfaces differently, and mandating mutual exclusiveness would severely limit implementation flexibility.

!!! tip "Workaround for Interfaces"
    Wrap interface values in classes:

```jo
interface Logger
  def log(msg: String): Unit
end

class ConsoleLoggerImpl(logger: Logger)
  view Logger = logger
end

class FileLoggerImpl(logger: Logger)
  view Logger = logger
end

type LoggerUnion = ConsoleLoggerImpl | FileLoggerImpl
```

### Why No Member Access on Union Types?

Direct member selection is prohibited on union types:

```jo
val s: Circle | Rectangle = ...
val x = s.width  // Error: Cannot access member on union type
```

**Rationale:**

1. **Type safety**: Different branches have different members; which one to access?
2. **Clarity**: Pattern matching makes the branch selection explicit
3. **No implicit consensus**: Unlike interfaces (where all implementors agree on member signatures), union branches are independent

**Alternative considered:** Allow member access if all branches have compatible members. Rejected because:

- Adds complexity to type checking
- Fragile: Adding a new branch without that member breaks existing code
- Implicit coupling between unrelated classes

!!! tip "Use Pattern Matching Instead"

```jo
val width = s match
  case c: Circle => c.r * 2  // Diameter as "width"
  case r: Rectangle => r.w
end
```

### Why Prohibit Type Parameters in Branches?

```jo
// Invalid
def foo[T](x: T | Int): Unit = ...
```

This is prohibited because:

1. **Type erasure**: In generic code, `T` is erased at runtime; no way to perform type tests against unknown type
2. **Unbounded alternatives**: `T` could be instantiated with infinitely many types
3. **Exhaustiveness**: Cannot check if all cases are covered when `T` is unknown

## Implementation

This section describes how union types are implemented in the compiler and runtime. These are implementation details that may vary across backends.

### Abstract Operations

Class identity is an abstract concept implemented differently on each platform. Every class has a unique identity, and the runtime provides two fundamental operations:

1. **Type test**: `isInstanceOf[C](obj)` - Test if an object is an instance of class C
2. **Safe cast**: `asInstanceOf[C](obj)` - Cast object to type C (assumes prior type test)

These operations are compiler intrinsics, not directly exposed to user code. Pattern matching on union types compiles to sequences of type tests and safe casts.

### Compilation Strategy

The compiler abstracts over platform differences. Pattern matching on union types is compiled uniformly using the abstract operations, which each backend implements appropriately:

**Source:**
```jo
def describe(s: Circle | Rectangle): String =
  s match
    case c: Circle => "circle: " + c.r
    case r: Rectangle => "rectangle: " + r.w
  end
```

**Compiled (abstract representation):**
```
if isInstanceOf[Circle](s) then
  val c = asInstanceOf[Circle](s)
  "circle: " + c.r
else if isInstanceOf[Rectangle](s) then
  val r = asInstanceOf[Rectangle](s)
  "rectangle: " + r.w
else
  throw MatchError
```

Each backend translates these abstract operations to its native mechanism (instanceof on JVM, constructor check on JS, class ID comparison on native).

### JVM Backend

JVM provides built-in class identity through Class objects:

```java
// Type test
boolean isCircle = obj instanceof Circle;

// Safe cast
Circle c = (Circle) obj;
```

Each class has a unique `Class<?>` object loaded by the classloader. The JVM's `instanceof` bytecode instruction and checkcast perform type tests and casts efficiently using these Class objects.

### JavaScript Backend

JavaScript uses constructor functions and prototypes:

```javascript
// Type test using constructor
function isCircle(obj) {
  return obj.constructor === Circle;
}

// Or using instanceof
boolean isCircle = obj instanceof Circle;

// Safe cast (just a type assertion in practice)
const c = obj;  // TypeScript: obj as Circle
```

Constructor functions serve as class identity. Each object has a `constructor` property pointing to its constructor function.

### Native Backend

For native compilation to x86/ARM, integer class IDs provide a lightweight mechanism:

Every class is assigned a unique global integer ID at compile time. Class instances store this ID in a header word:

```
Memory layout:
+----------------+
| Class ID (32)  |  ← Header word
+----------------+
| Field 1        |
| Field 2        |
| ...            |
+----------------+
```

Type test and cast operations:

```c
// Type test
bool isCircle(void* obj) {
  int classId = *((int*)obj - 1);  // Read header word
  return classId == CIRCLE_CLASS_ID;
}

// Safe cast (just returns the pointer)
Circle* asCircle(void* obj) {
  return (Circle*)obj;
}
```

**ID Assignment:**

The native backend assigns class IDs during code generation when it has the complete universe of classes available (from both source code and loaded `.sast` files):

1. **Compilation order**: IDs assigned in the order classes are encountered
2. **Monotonic**: Non-negative integers starting from 1
3. **Deterministic**: Same compilation inputs produce same ID assignments
