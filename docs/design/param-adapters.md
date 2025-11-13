# Parameter Adapters

## Overview

Parameter adapters provide a controlled form of argument conversion at function call sites without introducing implicit conversions or overloading. When an argument doesn't conform to a parameter's type, the compiler can automatically apply one of the declared adapter functions to convert the argument.

**Two forms:**

- **Function adapters** (`intToStr`): Apply a named function to the argument
- **Member adapters** (`.member`): Call a member on the argument

**Key principle:** Explicit at definition site, transparent at call site.

## Motivation

Eliminate repetitive conversion calls while maintaining explicitness:

```jo
// Without adapters
println(intToStr(42))
println(charToStr('x'))

// With adapters
println(42)
println('x')
```

This feature addresses two common programming needs:

1. **Controlled overloading**: Provides overloading-like functionality without the complexity and ambiguity issues of true overloading
2. **Explicit conversions**: Avoids powerful implicit conversions that harm code readability and maintainability, while still allowing convenient argument passing


## Syntax

### Basic Syntax

```
param_with_adapters = ident ":" type "with" "[" adapter_list "]"
adapter_list = adapter {"," adapter}
adapter = qualid | "." ident
```

**Function adapter:** Named function (qualified identifier)
**Member adapter:** Leading dot + member name (`.toString`, `.toInt`)

### Examples

```jo
// Function adapters
def println(s: String with [intToStr, charToStr]): Unit = ...

// Member adapters
def println(s: String with [.toString]): Unit = ...

// Mixed adapters
def show(s: String with [intToStr, .toString]): Unit = ...

// Multiple parameters
def format(x: String with [.toString], y: Int with [.toInt]): String = ...
```

## Semantics

### Type Checking Algorithm

For call `f(arg)` where parameter has type `T with [a1, a2, ..., an]`:

1. **Direct match:** If `arg : T`, use `arg` directly
2. **Try adapters in order:**
   - **Function adapter** `ai`: Type-check `ai(arg)`, use if result type is `T`
   - **Member adapter** `.member`: Type-check `arg.member`, use if result type is `T`
3. **First match wins:** Stop after first successful adapter
4. **No match:** Report type error

### Function Adapters

**Requirements:**

- Must be a named function (not lambda or variable)
- Exactly one parameter (no auto parameters, context parameters allowed)
- Return type must be parameter's type (or subtype)
- No type parameters

**Examples:**
```jo
def intToStr(x: Int): String = ...                     // Valid
def showInt(x: Int): String receives printer = ...     // Valid (context OK)
def badAdapter(x: Int, y: Int): String = ...           // Invalid (2 params)
def badAdapter[T](x: T): String = ...                  // Invalid (type param)
```

**Resolution:**
```jo
def println(s: String with [intToStr, boolToStr]): Unit = ...

println(42)     // Tries intToStr(42) ✓ → println(intToStr(42))
println(true)   // Tries intToStr(true) ✗, boolToStr(true) ✓
```

### Member Adapters

**Semantics:** Direct structural member lookup on argument type.

**Requirements:**

- Member must exist on argument type
- Member must have no parameters (zero-argument methods only)
- Result type must match parameter type (or subtype)

**Examples:**
```jo
def println(s: String with [.toString]): Unit = ...

println(42)     // 42.toString → String ✓
println(true)   // true.toString → String ✓
println([1,2])  // [1,2].toString → String ✓
```

**No implicit resolution:** Just checks if type has the member.

### Extensibility Comparison

| Aspect | Function Adapters | Member Adapters |
|--------|-------------------|-----------------|
| Extension point | Definition-site | Call-site |
| Type set | Closed (enumerated) | Open (structural) |
| Adding types | Modify function | Add member to type |
| Third-party types | Cannot extend | Can extend |

**Example - Extensibility:**
```jo
// Function adapter (closed)
def show(s: String with [intToStr, boolToStr]): Unit = ...
// To support Float, must modify show's definition

// Member adapter (open)
def show(s: String with [.toString]): Unit = ...
// Any type with .toString automatically works
```

**Example - Third-party composition:**
```jo
// Library A defines Widget (no toString)
type Widget = { id: Int }

// Library B adds toString to Widget
def Widget.toString: String = "Widget#" + this.id

// Library C defines display (unaware of Widget)
def display(s: String with [.toString]): Unit = ...

// User code - everything composes!
display(Widget(42))  // ✓ Works
```

### Adapter Order

Adapters are tried sequentially. First match wins.

```jo
def show(s: String with [intToStr, .toString]): Unit = ...

show(42)      // Tries intToStr(42) ✓ (stops, doesn't try .toString)
show(true)    // Tries intToStr(true) ✗, tries true.toString ✓
```

## Validation Rules

### Shadowed Adapters

Later adapters must not be shadowed by earlier adapters.

**Function adapter shadowing function adapter:**

For function adapters `[..., ai, ..., aj, ...]` where `j > i`, the argument type of `aj` must not conform to the argument type of `ai`.

```jo
def intToStr(x: Int): String = ...
def anotherIntToStr(x: Int): String = ...

// Error: anotherIntToStr shadowed by intToStr
def foo(s: String with [intToStr, anotherIntToStr]): Unit = ...
```

**Valid - Distinct types:**
```jo
def intToStr(x: Int): String = ...
def charToStr(x: Char): String = ...

def foo(s: String with [intToStr, charToStr]): Unit = ...  // OK
```

**Valid - Specific before general:**
```jo
type Animal = { name: String }
type Dog = { name: String, breed: String }

def dogToStr(x: Dog): String = ...
def animalToStr(x: Animal): String = ...

// OK: Dog is more specific, comes first
def show(s: String with [dogToStr, animalToStr]): Unit = ...
```

**Member adapter shadowing member adapter:**

Member adapters with the same member name are redundant.

```jo
// Error: .toString appears twice
def foo(s: String with [.toString, .toString]): Unit = ...
```

**Member adapter shadowing function adapter:**

A member adapter shadows a later function adapter if the function's argument type has the member with compatible return type.

```jo
def intToStr(x: Int): String = ...

// Error: intToStr shadowed by .toString (Int has toString: String)
def foo(s: String with [.toString, intToStr]): Unit = ...

// OK: Char doesn't have toString: String
def bar(s: String with [.toString, charToStr]): Unit = ...
```

**Function adapter before member adapter (OK):**

Function adapters don't shadow member adapters. The function adapter handles a closed type set (its parameter type), while the member adapter handles an open type set (all types with the member), so the member adapter remains reachable for other types.

```jo
def intToStr(x: Int): String = ...

// OK: intToStr handles Int, .toString handles other types
def foo(s: String with [intToStr, .toString]): Unit = ...
```

### Varargs Parameters

Adapters apply to individual elements, not the entire sequence.

```jo
def intToStr(i: Int): String = ...
def printAll(items: ..String with [intToStr]): Unit = ...

printAll(1, 2, 3)        // printAll(intToStr(1), intToStr(2), intToStr(3))
printAll("a", 42, "c")   // printAll("a", intToStr(42), "c")
```

**Splice with adapters:**
```jo
val numbers = [1, 2, 3]  // List[Int]
printAll(..numbers)      // printAll(..numbers.map(intToStr))
```

## Restrictions

### Auto Parameters

Auto parameters cannot have adapters.

```jo
def foo(auto ctx: Context with [adapter]): Unit = ...  // Invalid
```

### Polymorphism

Adapters and parameters with type variables cannot be polymorphic.

```jo
// Invalid - adapter has type parameter
def genericAdapter[T](x: T): String = ...
def foo(s: String with [genericAdapter]): Unit = ...

// Invalid - parameter has type variable
def process[T](items: List[T] with [arrayToList]): Unit = ...
```

**Rationale:** Simplifies type checking and avoids type inference ambiguity.

### No Chaining

Adapters are not chained or composed.

```jo
def intToBool(x: Int): Bool = x != 0
def boolToStr(b: Bool): String = if b then "true" else "false"

def process(s: String with [intToBool, boolToStr]): Unit = ...

process(5)  // Error: intToBool(5) returns Bool, not String
```

## Examples

### Basic Usage

```jo
def intToStr(i: Int): String = intToString(i)
def charToStr(c: Char): String = charToString(c)

def println(s: String with [intToStr, charToStr, .toString]): Unit = ...

println("hello")  // Direct
println(42)       // intToStr(42)
println('x')      // charToStr('x')
println(true)     // true.toString (member adapter)
```

### Context-Dependent Conversion

```jo
param hexMode: Bool = false

def intToStr(n: Int): String receives hexMode =
  if hexMode then "0x" + intToHexString(n)
  else intToString(n)

def display(msg: String with [intToStr]): Unit = println(msg)

display(42)                        // "42" (decimal)
display(255) with hexMode = true   // "0xff" (hexadecimal)
```

### Extensible Printing

```jo
// Define once with member adapter
def show(x: String with [.toString]): Unit = println(x)

// Works with any type that has toString
show(42)              // Int.toString
show(true)            // Bool.toString
show([1, 2, 3])       // List.toString

// User-defined types work automatically
type Point = { x: Int, y: Int }
def Point.toString: String = "(" + this.x + "," + this.y + ")"
show(Point(3, 4))     // Point.toString
```

## Design Rationale

### Why Definition-Site?

Adapters at definition site provide:

- **Discoverability:** Conversions visible in signature
- **Control:** Function author controls acceptable conversions
- **Simplicity:** No ambiguity about which adapters apply

### Why Not Implicit Conversions?

Unlike Scala-style implicits:

- **Explicit:** Visible in function signature
- **Scoped:** Apply only to specific parameters
- **Ordered:** First match wins (no ambiguity)
- **Limited:** No chaining prevents unexpected conversions

### Why Not Overloading?

Compared to overloading:

- **Simpler:** No complex resolution rules
- **Clearer:** Conversion functions explicitly named
- **Flexible:** Adapters can have context parameters
- **Maintainable:** Add conversions without new overloads

## Implementation Notes

### Type Checking (Namer.scala)

1. Check if argument conforms to parameter type
2. If not, retrieve adapter list from parameter
3. For each adapter in order:
     - **Function adapter:** Resolve name, validate signature, try application
     - **Member adapter:** Check if argument type has member with correct return type
4. Transform tree with first successful adapter
5. Report error if all adapters fail

### Validation (Function Definition)

1. Resolve adapter names (function adapters only)
2. Check adapter signatures (single param, no type/auto params)
3. Verify return types conform to parameter type
4. Check no shadowed adapters

All validation at definition time for early error detection.
