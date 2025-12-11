# Duck Types

## Overview

Duck types encapsulate a target type together with a list of adapters. They enable parameters to accept arguments that can be automatically converted to the required type through a defined set of adapter functions.

**Syntax:**

```jo
type StringLike = like String with [intToStr, charToStr, .toString]
```

**Usage:**

```jo
def println(s: StringLike): Unit = ...

// Now accepts String directly, Int via intToStr, Char via charToStr,
// or any type with .toString method
println("hello")  // Direct
println(42)       // Via intToStr
println('x')      // Via charToStr
```

Duck types can be used inline or named for reuse, improving code clarity and maintainability.

## Motivation

Functions often need to accept arguments of different types that can be converted to a common target type. Without duck types, each function would need its own conversion logic, leading to repetitive code:

```jo
// Without duck types - repetitive conversion logic
def println(s: String): Unit = ...
def printlnInt(i: Int): Unit = println(intToStr(i))
def printlnChar(c: Char): Unit = println(charToStr(c))

// Multiple overloads needed for each function
def display(msg: String): Unit = ...
def displayInt(i: Int): Unit = display(intToStr(i))
def displayChar(c: Char): Unit = display(charToStr(c))
```

Duck types eliminate this repetition through reusable definitions:

```jo
// With duck types - clean and DRY
type Printable = like String with [intToStr, charToStr, .toString]

def println(s: Printable): Unit = ...
def display(msg: Printable): Unit = ...
def format(x: Printable): String = ...

// All automatically accept String, Int, Char, and any type with .toString
println("hello")
println(42)
println('x')
```

**Benefits:**

1. **Define once, use everywhere** - Adapter lists maintained in one place
2. **Semantic naming** - `Printable` conveys intent clearly
3. **Consistency** - Same adapters in same order across codebase
4. **Early validation** - Shadowing errors caught at type definition, not at each use site
5. **Maintainability** - Add or remove adapters by updating one definition
6. **No overloading needed** - Single function accepts multiple types cleanly

## Syntax

```
duck_type = "like" type "with" "[" adapter_list "]"
adapter_list = adapter {"," adapter}
adapter = qualid | "." ident
```

Duck types can be used inline in type positions or given names via type aliases:

```jo
// Named definition
type Printable = like String with [intToStr, .toString]

// Inline usage
def println(s: like String with [intToStr, .toString]): Unit = ...
```

**Adapters come in two forms:**

1. **Function adapters** (`qualid`) - Named functions that convert arguments
2. **Member adapters** (`.ident`) - Methods called on the argument

**Examples:**

```jo
// Basic duck type
type Printable = like String with [.toString]

// Multiple adapters
type StringLike = like String with [intToStr, charToStr, byteToStr, .toString]

// Numeric conversions
type NumericString = like String with [intToStr, floatToStr, byteToStr]
```

## Semantics

### Type Equivalence

**Duck types and their base types conform to each other.** A duck type `like T with [...]` and its base type `T` have a mutual subtyping relationship:

```jo
type StringLike = like String with [intToStr]

def foo(s: String): Unit = ...
def bar(s: StringLike): Unit = ...

val x: StringLike = "hello"
foo(x)  // ✓ StringLike conforms to String

val y: String = "world"
bar(y)  // ✓ String conforms to StringLike
```

This mutual conformance means:

- A duck type can be used where its base type is expected
- The base type can be used where the duck type is expected
- No wrapper/unwrapper functions needed
- No runtime type distinction
- Adapters apply at the point where a value is converted to the duck type

### Duck Type Behavior

Duck types carry adapter information. When used as an expected type, the compiler applies an adapter resolution algorithm to automatically convert arguments:

```jo
type Printable = like String with [intToStr, .toString]

def println(s: Printable): Unit = ...

// Calls are automatically adapted:
println("hello")  // Direct: String matches
println(42)       // Adapted: intToStr(42) applied
println(point)    // Adapted: point.toString applied (if point has .toString)
```

### Adapter Activation

Adapters activate during type checking whenever there is an **expected type** that is a duck type and the actual value type does not directly conform. This applies in all contexts:

```jo
type StringLike = like String with [intToStr, .toString]

// Parameter position
def println(s: StringLike): Unit = ...
println(42)  // OK: intToStr(42) applied

// Variable assignment
val x: StringLike = 42  // OK: intToStr(42) applied
val y: StringLike = "hello"  // OK: Direct match, no adapter needed

// Return type
def getNumber(): StringLike = 42  // OK: intToStr(42) applied

// Field initialization
class Example
  val msg: StringLike = 42  // OK: intToStr(42) applied
end
```

**Key principle:** If the value type already conforms to the base type, no adapter is applied. Adapters only activate when type conformance would otherwise fail.

```jo
type StringLike = like String with [intToStr]

val a: StringLike = "hello"  // Direct: String conforms to String
val b: StringLike = 42       // Adapted: Int doesn't conform, try intToStr(42)
```

### Resolution Algorithm

When type checking requires adapting a value to a duck type, the compiler applies the following resolution algorithm:

Given an expected type `like T with [a1, a2, ..., an]` and actual value `v`:

1. **Direct match:** If `v : T`, use `v` directly (no adapter needed)
2. **Try adapters in order:**
   - **Function adapter** `ai`: Type-check `ai(v)`. If successful and result type is `T`, use `ai(v)`
   - **Member adapter** `.member`: Type-check `v.member`. If successful and result type is `T`, use `v.member`
3. **First match wins:** Stop after first successful adapter
4. **No match:** Report type error if no adapter succeeds

**Example:**

```jo
type StringLike = like String with [intToStr, charToStr, .toString]

def show(s: StringLike): Unit = ...

show("hello")  // Step 1: Direct match (String : String)
show(42)       // Step 2: Try intToStr(42) → String ✓
show('x')      // Step 2: Try intToStr('x') ✗, try charToStr('x') → String ✓
show(point)    // Step 2: Try intToStr(point) ✗, try charToStr(point) ✗,
               //         try point.toString → String ✓
```

### Function Adapters

**Function adapters** are named functions that convert arguments to the target type.

**Requirements:**

- Must be a named function (not lambda or variable)
- Exactly one regular parameter (context parameters are allowed)
- Return type must match the target type (or be a subtype)
- No type parameters
- No auto parameters

**Examples:**

```jo
def intToStr(x: Int): String = ...                     // Valid
def showInt(x: Int): String receives printer = ...     // Valid (context parameter OK)
def badAdapter(x: Int, y: Int): String = ...           // Invalid (2 parameters)
def badAdapter[T](x: T): String = ...                  // Invalid (type parameter)
```

**Resolution:**

```jo
type Printable = like String with [intToStr, boolToStr]

def println(s: Printable): Unit = ...

println(42)     // Tries intToStr(42) ✓ → println(intToStr(42))
println(true)   // Tries intToStr(true) ✗, boolToStr(true) ✓
```

### Member Adapters

**Member adapters** call a member method on the argument.

**Semantics:** Direct structural member lookup on argument type.

**Requirements:**

- Member must exist on argument type
- Member must have no regular parameters (context parameters are allowed)
- Result type must match target type (or be a subtype)
- Context parameters are propagated through the adapter

**Examples:**

```jo
// Basic member adapter
type Printable = like String with [.toString]

def println(s: Printable): Unit = ...

println(42)     // 42.toString → String ✓
println(true)   // true.toString → String ✓

// Member adapter with context parameter
param indent: Int

class Document
  def format: String receives indent = intToStr(indent) + ": content"
end

type Formatted = like String with [.format]

def show(s: Formatted): Unit = ...

val doc = new Document()
show(doc) with indent = 5   // doc.format → "5: content" ✓
```

**Context parameter propagation:** When a member method requires context parameters, those requirements are propagated to the calling function.

### Adapter Order

Adapters are tried sequentially. First match wins.

```jo
type Printable = like String with [intToStr, .toString]

def show(s: Printable): Unit = ...

show(42)      // Tries intToStr(42) ✓ (stops, doesn't try .toString)
show(true)    // Tries intToStr(true) ✗, tries true.toString ✓
```

### Varargs Parameters

Adapters apply to individual varargs elements, not the entire sequence.

```jo
type Printable = like String with [intToStr, .toString]

def printAll(items: ..Printable): Unit = ...

printAll(1, 2, 3)        // printAll(intToStr(1), intToStr(2), intToStr(3))
printAll("a", 42, "c")   // printAll("a", intToStr(42), "c")
```

**Splice with adapters:**

```jo
val numbers = [1, 2, 3]  // List[Int]
printAll(..numbers)      // printAll(..numbers.map(intToStr))
```

## Validation Rules

### Shadowed Adapters

Later adapters must not be shadowed by earlier adapters. Shadowing validation occurs at type definition time, ensuring adapter lists are well-formed before use.

#### Function Adapter Shadowing Function Adapter

For function adapters `[..., ai, ..., aj, ...]` where `j > i`, the argument type of `aj` must not conform to the argument type of `ai`.

**Invalid - shadowed adapter:**

```jo
def intToStr(x: Int): String = ...
def anotherIntToStr(x: Int): String = ...

// Error: anotherIntToStr shadowed by intToStr
type StringLike = like String with [intToStr, anotherIntToStr]
```

**Valid - Distinct argument types:**

```jo
def intToStr(x: Int): String = ...
def charToStr(x: Char): String = ...

// OK: Different argument types
type StringLike = like String with [intToStr, charToStr]
```

#### Member Adapter Shadowing Member Adapter

Member adapters with the same member name are redundant.

```jo
// Error: .toString appears twice
type Printable = like String with [.toString, .toString]
```

#### Member Adapter Shadowing Function Adapter

A member adapter shadows a later function adapter if the function's argument type has the member with compatible return type.

```jo
def intToStr(x: Int): String = ...

// Error: intToStr shadowed by .toString (Int has toString: String)
type Printable = like String with [.toString, intToStr]

// OK: Char doesn't have toString: String (if it doesn't)
type Display = like String with [.toString, charToStr]
```

#### Function Adapter Before Member Adapter (OK)

Function adapters don't shadow member adapters. The function adapter handles a closed type set (its parameter type), while the member adapter handles an open type set (all types with the member), so the member adapter remains reachable for other types.

```jo
def intToStr(x: Int): String = ...

// OK: intToStr handles Int, .toString handles other types
type Printable = like String with [intToStr, .toString]
```

### Non-Nesting Constraint

The base type in a duck type definition must be a plain type, not another duck type:

```jo
type A = like String with [intToStr]
type B = like A with [charToStr]  // Error: A is already a duck type
```

**Rationale:** Prevents confusing nested adapter semantics and unclear precedence rules.

**Valid alternative:**

```jo
type A = like String with [intToStr]
type B = like String with [intToStr, charToStr]  // OK: Independent definition
```

### Polymorphism Restrictions

Adapter functions cannot have type parameters:

```jo
// Invalid - adapter has type parameter
def genericAdapter[T](x: T): String = ...
type Display = like String with [genericAdapter]  // Error
```

**Rationale:** Simplifies type checking and avoids type inference ambiguity.

### No Chaining

Adapters are not chained or composed. Each adapter must directly produce the target type.

```jo
def intToBool(x: Int): Bool = x != 0
def boolToStr(b: Bool): String = if b then "true" else "false"

type Display = like String with [intToBool, boolToStr]

def process(s: Display): Unit = ...

process(5)  // Error: intToBool(5) returns Bool, not String
```

## Examples

### Basic Usage

```jo
class Point(x: Int, y: Int)
  def toString: String = "Point(" + intToString(x) + ", " + intToString(y) + ")"
end

def intToStr(i: Int): String = intToString(i)
def charToStr(c: Char): String = charToString(c)

type Printable = like String with [intToStr, charToStr, .toString]

def println(s: Printable): Unit receives IO.stdout = ...
def display(msg: Printable): Unit receives IO.stdout = ...

println("hello")  // Direct: String
println(42)       // Adapter: intToStr(42)
println('x')      // Adapter: charToStr('x')

val p = new Point(3, 4)
println(p)        // Adapter: p.toString
```

### Numeric Conversions

```jo
def intToStr(i: Int): String = ...
def floatToStr(f: Float): String = ...
def byteToStr(b: Byte): String = ...

type NumericString = like String with [intToStr, floatToStr, byteToStr]

def parse(s: NumericString): Int = ...

parse("123")     // Direct: String
parse(456)       // Adapter: intToStr(456)
parse(3.14)      // Adapter: floatToStr(3.14)
```

### Reusable Adapter Patterns

```jo
// Domain-specific duck types
type UserId = like String with [userIdToStr, intToStr]
type Timestamp = like String with [timestampToStr, intToStr]
type ErrorMessage = like String with [errorToStr, .toString]

def log(msg: ErrorMessage): Unit = ...
def recordUser(id: UserId): Unit = ...
def recordTime(ts: Timestamp): Unit = ...
```

### Varargs with Duck Types

```jo
type Printable = like String with [intToStr, .toString]

def printAll(items: ..Printable): Unit = ...

printAll("a", 42, "c", true)  // Mixed types, each adapted individually
```

### Context-Dependent Adapters

```jo
param hexMode: Bool = false

def intToStr(n: Int): String receives hexMode =
  if hexMode then "0x" + intToHexString(n)
  else intToString(n)

type NumDisplay = like String with [intToStr]

def show(x: NumDisplay): Unit = println(x)

show(255)                        // "255"
show(255) with hexMode = true    // "0xff"
```

### Compositional Conversions

Member adapters with auto parameters enable compositional conversions—a key use case for generic collections:

```jo
class List[T]
  def toString(auto show: T => String with [[T].toString]): String = ...
end

type Printable = like String with [.toString]

def println(s: Printable): Unit = ...

// Now these just work:
println([1, 2, 3])           // List[Int].toString auto-resolves Int => String via [Int].toString
println(["a", "b"])          // List[String].toString auto-resolves String => String
println([[1, 2], [3, 4]])    // List[List[Int]].toString recursively resolves via [List[Int]].toString

// String interpolation also works:
val xs = [1, 2, 3]
println("Numbers: $xs")      // Uses xs.toString with auto-resolved element conversion
```

This pattern allows collection types to automatically adapt their elements for conversion. The member candidate `[T].toString` enables the List's toString method to automatically find the element conversion function.

## Design Rationale

### Auto Parameter Asymmetry

**Function adapters cannot have auto parameters, but member adapters can.** This asymmetry exists for simplicity and explicitness:

```jo
// Function adapter: CANNOT have auto parameters
def formatInt(x: Int)(auto formatter: Formatter): String = ...  // ✗ Invalid

// Member adapter: CAN have auto parameters
class Box[T]
  def toString(auto show: T => String with [[T].toString]): String = ...  // ✓ Valid
end
```

**Rationale:**

1. **Adaptation is already implicit** - Function adapters already provide implicit conversion from argument type to target type. Adding auto parameters would create two levels of implicitness, making the behavior harder to understand.

2. **Type is known for function adapters** - Function adapter signatures explicitly declare both parameter type and return type. There's no ambiguity about what types are involved, so auto resolution isn't needed.

3. **Member adapters need auto for composition** - Unlike function adapters, member methods often need to work generically (like `List[T].toString`). The type `T` isn't known at the duck type definition site, so auto parameters enable compositional conversions.

4. **Explicitness where possible** - Function adapters are defined separately and can be made as explicit as needed. Member adapters are structural lookups where we can't control the implementation, so auto parameters are a necessary tool for generic composition.

**Example showing why member adapters need auto:**

```jo
// Without auto parameters, we couldn't do:
type Printable = like String with [.toString]

// This works because List[T].toString has:
//   def toString(auto show: T => String with [[T].toString]): String
println([1, 2, 3])        // List[Int] - auto resolves Int => String
println([["a"], ["b"]])   // List[List[String]] - auto resolves recursively
```

The asymmetry is a deliberate design choice: keep function adapters simple and explicit, while allowing member adapters the flexibility needed for generic composition.

### Why No Nesting?

Prohibiting nested duck types (`like (like T with [...]) with [...]`) prevents:

- **Confusion** - Unclear which adapters apply when
- **Complexity** - No need to define adapter precedence rules
- **Maintenance issues** - Changes to base duck type affect derived types

Independent definitions are clearer and more maintainable.

### Why Not Implicit Conversions?

Unlike Scala-style implicits, duck types are:

- **Explicit** - Visible in function signature
- **Scoped** - Apply only to specific parameters
- **Ordered** - First match wins (no ambiguity)
- **Limited** - No chaining prevents unexpected conversions

### Why Not Overloading?

Compared to overloading, duck types provide:

- **Simpler** - No complex resolution rules
- **Clearer** - Conversion functions explicitly named
- **Flexible** - Adapters can have context parameters
- **Maintainable** - Add conversions without new overloads

### Naming Conventions

**Recommended suffixes:**

- **`-able`** - Indicates adaptability: `Printable`, `Comparable`, `Serializable`
- **`-Like`** - Indicates similarity: `StringLike`, `IntLike`
- **`-Convertible`** - Indicates conversion: `StringConvertible`

**Contrast with view types:**

- **Duck types** - "What can become X?" → `Printable`, `StringLike`
- **View types** - "X enriched with Y" → `RichInt`, `RichString`

```jo
// Duck type: many things → String
type Printable = like String with [intToStr, .toString]

// View type (future): Int → many capabilities
type RichInt = view Int as IntOps with createIntOps
```

## Summary

Duck types provide adapter specifications that enable parameters to automatically accept multiple types through a defined conversion mechanism. They can be used inline or given names for reuse, eliminating repetition while improving code clarity and maintainability. Duck types bring the flexibility of duck typing to static typing with compile-time safety. The `like` keyword expresses behavioral compatibility—"things that can be treated like String"—without the complexity of implicit conversions or overloading.
