# Name Universes

Jo has four separate name universes to avoid naming conflicts and provide better error messages. Each universe is resolved independently based on the syntactic position where a name appears.

## The Four Name Universes

### 1. Term Names

Term names refer to runtime values and include:

- Function names defined with `def`
- Parameter names in function signatures
- Variables defined with `val`

**Example:**
```jo
def double(x: Int): Int = x + x   // 'double' is a term name, 'x' is a parameter (term name)

val result = 10                   // 'result' is a term name
```

### 2. Type Names

Type names refer to types and include:

- Class names
- Type definitions created with `type`
- Type parameters

**Example:**
```jo
type MyInt = Int                  // 'MyInt' is a type name

class Point(x: Int, y: Int)       // 'Point' is a type name

def foo[T](x: T): T = x          // 'T' is a type name (type parameter)
```

### 3. Pattern Names

Pattern names refer to pattern definitions that can be used in pattern matching:

- Pattern definitions created with `pattern`
- Pattern parameters (output parameters in pattern definitions)

**Example:**
```jo
class Point(x: Int, y: Int)

pattern Point(x: Int, y: Int): Point =  // 'Point', `x`, `y` are pattern names
  case p then x = p.x, y = p.y
```

### 4. Container Names

Container names refer to organizational units:

- Namespace names
- Section names

**Example:**
```jo
namespace MyLib                   // 'MyLib' is a container name

class Point(x: Int, y: Int)

section Point                     // 'Point' is a container name
  def (c: Point) show: String = ...
end
```

## Name Resolution Rules

The resolution of names depends on the **syntactic position** where they appear:

### Expression Position

**Rule:** Names are resolved as **term names**.

An expression position is any location where a term (runtime value) is expected.

**Examples:**
```jo
def main =
  val x = double 10          // 'double' resolved as term name
  println x                  // 'println' and 'x' resolved as term names

  val list = Cons 1 Nil      // 'Cons' and 'Nil' resolved as term names (data constructors)
```

### Type Position

**Rule:** Names are resolved as **type names**.

A type position occurs in:

- Type annotations (e.g., `x: Int`)
- Type arguments (e.g., `foo[T]`)
- Type patterns in match expressions

**Examples:**
```jo
def foo(x: Int): String = ...           // 'Int' and 'String' resolved as type names

val list: List[Int] = ...               // 'List' and 'Int' resolved as type names
```

### Pattern Position

**Rule:** Names are resolved **pattern names**.

A pattern position occurs in:

- `match` expression cases
- Pattern definitions

**Examples:**
```jo
match myList
  case Nil => 0                         // 'Nil' resolved as pattern name
  case Cons head tail => 1              // 'Cons' resolved as pattern name

pattern Len(x: Int): List[Int] =
  case Nil then x = 0                   // 'Nil' resolved as pattern name
  case Cons _ (Len y) then x = y + 1    // 'Len' resolved as pattern name
```

### Container Position

**Rule:** Names are resolved as **container names**.

A container position occurs in:

- Import clauses
- Qualifiers in type selections

**Examples:**
```jo
import MyLib                             // 'MyLib' resolved as container name

type T = MyLib.MyType                    // 'MyLib' resolved as container name
```

## Special Resolution Rule for Term Identifiers

When typing a term identifier, special rules apply based on the **target type**.

### When Target Type is a Selection

1. **First attempt:** Resolve the name as a **term name**
2. **Check conformance:** If the term exists, check if it conforms to the selection type
3. **Fallback:** If the term doesn't exist OR doesn't conform to the selection, search for a **container name**

**Example:**
```jo
section List
  def empty[T]: List[T] = ...
end

def main =
  // 'List' is first tried as term name, not found or doesn't conform,
  // then resolved as container name (section)
  List.empty[Int]
```

In this example, when typing `List.empty`, the compiler:

1. Tries to resolve `List` as a term name
2. Either doesn't find it, or finds it but sees it doesn't have the expected selection type
3. Falls back to resolving `List` as a container name (the section `List`)

### When Target Type is NOT a Selection

**Rule:** Resolve as a **term name** only.

**Example:**
```jo
def double(x: Int): Int = x + x

def main =
  val fn = double          // 'double' resolved as term name only
  println (fn 10)
```

## Scoping Rules

Scoping rules determine **where** names are visible in the code. Jo has different scoping rules for container-level definitions and local definitions within functions.

### Container-Level Scoping

At the container level (namespace or section level), global names are available according to their **visibility specification**.

**Visibility Modifiers:**

- **Public** (default): Visible everywhere
- **Private**: Visible only within the immediate container (section or namespace)
- **Private[ContainerName]**: Visible within the specified container and its nested containers

**Examples:**
```jo
namespace MyLib
  def publicFunc(): Int = 42              // visible everywhere

  private def helperFunc(): Int = 10      // visible only within MyLib

  section Inner
    private[MyLib] def sharedFunc(): Int = 5   // visible within MyLib and nested containers

    def useHelper(): Int = helperFunc()   // OK: within MyLib
    def useShared(): Int = sharedFunc()   // OK: within MyLib
  end

  def test(): Int = sharedFunc()          // OK: within MyLib
end

def external(): Int =
  MyLib.publicFunc()                      // OK: public
  MyLib.helperFunc()                      // Error: private to MyLib
  MyLib.Inner.sharedFunc()                // Error: private[MyLib]
```

**Coherence:** Private symbols cannot leak into more public definitions. A definition's signature cannot contain symbols that are more restricted in visibility than the definition itself.

```jo
section A
  private type Internal = Int

  def publicFunc(x: Internal): Int = x    // Error: Internal is private, publicFunc is public
  private def privateFunc(x: Internal): Int = x  // OK: both are private
end
```

For more details on visibility control and coherence checking, see the [Visibility and Coherence](../../design/visibility-coherence/) design document.

### Local Scoping

Names introduced by definitions (`val`, `var`, `def`, etc.) inside a block are only visible for **later phrases** within that block.

**Key principles:**

1. **Sequential visibility:** A local name is visible only after its definition
2. **Block scoping:** Names are scoped to the block where they're defined
3. **Shadowing:** Inner blocks can shadow names from outer blocks

**Examples:**
```jo
def example(): Int =
  val x = 10                    // x visible from this point onward
  val y = x + 5                 // OK: x is visible, y visible from here

  if y > 10 then
    val z = y * 2               // z visible only within this if-block
    println z
    z                           // OK: z is visible here
  else
    println y                   // OK: y is visible
    // println z                // Error: z not visible outside its block
    y

def loops(): Unit =
  var i = 0
  while i < 10 do
    val square = i * i          // square visible only within loop body
    println square
    i = i + 1
  // println square              // Error: square not visible outside loop
  println i                     // OK: i is visible

def shadowing(): Int =
  val x = 10
  if true then
    val x = 20                  // shadows outer x within this block
    x                           // refers to inner x (20)
  else
    x                           // refers to outer x (10)
```

**Nested function definitions:**
```jo
def outer(): Int =
  val x = 10

  def inner(): Int =            // inner is visible after this point
    x + 5                       // OK: inner can access outer's x

  inner()                       // OK: call inner
```

### Function Parameters

Function parameters are visible throughout the function body (after their declaration):

```jo
def compute(x: Int, y: Int): Int =
  val sum = x + y               // x and y are visible
  val product = x * y
  sum + product

def multiParam(a: Int)(b: Int)(c: Int): Int =
  a + b + c                     // all parameters visible in body
```

### Pattern Matching Scope

Variables bound in pattern matching are scoped to the corresponding case:

```jo
match myList
  case Nil =>
    println "empty"

  case Cons head tail =>
    println head                // head and tail visible only in this case
    println tail
```

General rules:

- A pattern variable must be _definitely bound exactly once_.
- Only a definitely bound pattern variable can be used as a term variable in later patterns or case body.

!!! info "Work in progress"

    The specification for pattern names is incomplete and will be completed.

## Summary

| Name Universe   | Position                      | Example                          |
|-----------------|-------------------------------|----------------------------------|
| Term names      | Expression                    | `double 10`, `Cons 1 Nil`        |
| Type names      | Type annotation               | `x: Int`, `List[String]`         |
| Pattern names   | Pattern match                 | `case Nil =>`, `case Size(n) =>` |
| Container names | Import clause, type qualifier | `import MyLib`, `MyLib.MyType`   |
