# Duck Types

A duck type `T :- [a1, a2, ..., an]` pairs a target type `T` with an ordered list of
adapters. When a value is checked against the duck type and does not directly conform to
`T`, the compiler tries each adapter in order and uses the first that succeeds.

## Syntax

```
duck_type = type ":-" "[" adapter_list "]"
adapter_list = adapter {"," adapter}
adapter = qualid | "." ident
```

Duck types can be used inline in type positions or given names via type aliases:

```jo
// Named definition
type Printable = String :- [intToStr, .toString]

// Inline usage
def println(s: String :- [intToStr, .toString]): Unit = ...
```

**Adapters come in two forms:**

1. **Function adapters** (`qualid`) - Named functions that convert arguments
2. **Member adapters** (`.ident`) - Methods called on the argument

**Examples:**

```jo
// Basic duck type
type Printable = String :- [.toString]

// Multiple adapters
type StringLike = String :- [intToStr, charToStr, byteToStr, .toString]

// Numeric conversions
type NumericString = String :- [intToStr, floatToStr, byteToStr]
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
type Printable = String :- [.toString]

def println(s: Printable): Unit = ...

println(42)     // 42.toString → String ✓
println(true)   // true.toString → String ✓

// Member adapter with context parameter
param indent: Int

class Document
  def format: String receives indent = intToStr(indent) + ": content"
end

type Formatted = String :- [.format]

def show(s: Formatted): Unit = ...

val doc = new Document()
with indent = 5 in show(doc)   // doc.format → "5: content" ✓
```

**Context parameter propagation:** When a member method requires context parameters, those requirements are propagated to the calling function.

### Adapter Order

Adapters are tried sequentially. First match wins.

```jo
type Printable = String :- [intToStr, .toString]

def show(s: Printable): Unit = ...

show(42)      // Tries intToStr(42) ✓ (stops, doesn't try .toString)
show(true)    // Tries intToStr(true) ✗, tries true.toString ✓
```

## Semantics

### Type Equivalence

**Duck types and their base types conform to each other.** A duck type `T :- [...]` and its base type `T` have a mutual subtyping relationship:

```jo
type StringLike = String :- [intToStr]

def foo(s: String): Unit = ...
def bar(s: StringLike): Unit = ...

val x: StringLike = "hello"
foo(x)  // ✓ StringLike conforms to String

val y: String = "world"
bar(y)  // ✓ String conforms to StringLike
```

### Duck Type Behavior

Duck types carry adapter information. When used as an expected type, the compiler applies an adapter resolution algorithm to automatically convert arguments:

```jo
type Printable = String :- [intToStr, .toString]

def println(s: Printable): Unit = ...

// Calls are automatically adapted:
println("hello")  // Direct: String matches
println(42)       // Adapted: intToStr(42) applied
println(point)    // Adapted: point.toString applied (if point has .toString)
```

### Adapter Activation

Adapters activate during type checking whenever there is an **expected type** that is a duck type and the actual value type does not directly conform. This applies in all contexts:

```jo
type StringLike = String :- [intToStr, .toString]

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
type StringLike = String :- [intToStr]

val a: StringLike = "hello"  // Direct: String conforms to String
val b: StringLike = 42       // Adapted: Int doesn't conform, try intToStr(42)
```

### Resolution Algorithm

When type checking requires adapting a value to a duck type, the compiler applies the following resolution algorithm:

Given an expected type `T :- [a1, a2, ..., an]` and actual value `v`:

1. **Direct match:** If `v : T`, use `v` directly (no adapter needed)
2. **Try adapters in order:**
     - **Function adapter** `ai`: Type-check `ai(v)`. If successful and result type is `T`, use `ai(v)`
     - **Member adapter** `.member`: Type-check `v.member`. If successful and result type is `T`, use `v.member`
3. **First match wins:** Stop after first successful adapter
4. **No match:** Report type error if no adapter succeeds

**Example:**

```jo
type StringLike = String :- [intToStr, charToStr, .toString]

def show(s: StringLike): Unit = ...

show("hello")  // Step 1: Direct match (String : String)
show(42)       // Step 2: Try intToStr(42) → String ✓
show('x')      // Step 2: Try intToStr('x') ✗, try charToStr('x') → String ✓
show(point)    // Step 2: Try intToStr(point) ✗, try charToStr(point) ✗,
               //         try point.toString → String ✓
```

### Varargs Parameters

Adapters apply to individual varargs elements, not the entire sequence.

```jo
type Printable = String :- [intToStr, .toString]

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
type StringLike = String :- [intToStr, anotherIntToStr]
```

**Valid - Distinct argument types:**

```jo
def intToStr(x: Int): String = ...
def charToStr(x: Char): String = ...

// OK: Different argument types
type StringLike = String :- [intToStr, charToStr]
```

#### Member Adapter Shadowing Member Adapter

Member adapters with the same member name are redundant.

```jo
// Error: .toString appears twice
type Printable = String :- [.toString, .toString]
```

#### Member Adapter Shadowing Function Adapter

A member adapter shadows a later function adapter if the function's argument type has the member with compatible return type.

```jo
def intToStr(x: Int): String = ...

// Error: intToStr shadowed by .toString (Int has toString: String)
type Printable = String :- [.toString, intToStr]

// OK: Char doesn't have toString: String (if it doesn't)
type Display = String :- [.toString, charToStr]
```

#### Function Adapter Before Member Adapter (OK)

Function adapters don't shadow member adapters. The function adapter handles a closed type set (its parameter type), while the member adapter handles an open type set (all types with the member), so the member adapter remains reachable for other types.

```jo
def intToStr(x: Int): String = ...

// OK: intToStr handles Int, .toString handles other types
type Printable = String :- [intToStr, .toString]
```

### Non-Nesting Constraint

The base type in a duck type definition must be a plain type, not another duck type:

```jo
type A = String :- [intToStr]
type B = A :- [charToStr]  // Error: A is already a duck type
```

**Rationale:** Prevents confusing nested adapter semantics and unclear precedence rules.

**Valid alternative:**

```jo
type A = String :- [intToStr]
type B = String :- [intToStr, charToStr]  // OK: Independent definition
```

### Polymorphism Restrictions

Adapter functions cannot have type parameters:

```jo
// Invalid - adapter has type parameter
def genericAdapter[T](x: T): String = ...
type Display = String :- [genericAdapter]  // Error
```

**Rationale:** Simplifies type checking and avoids type inference ambiguity.

### No Chaining

Adapters are not chained or composed. Each adapter must directly produce the target type.

```jo
def intToBool(x: Int): Bool = x != 0
def boolToStr(b: Bool): String = if b then "true" else "false"

type Display = String :- [intToBool, boolToStr]

def process(s: Display): Unit = ...

process(5)  // Error: intToBool(5) returns Bool, not String
```

