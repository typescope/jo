# Parameter Adapters

## Overview

Parameter adapters provide a controlled form of argument conversion at function call sites without introducing implicit conversions or overloading. When an argument doesn't conform to a parameter's type, the compiler can automatically apply one of the declared adapter functions to convert the argument.

## Motivation

This feature addresses two common programming needs:

1. **Controlled overloading**: Provides overloading-like functionality without the complexity and ambiguity issues of true overloading
2. **Explicit conversions**: Avoids powerful implicit conversions that harm code readability and maintainability, while still allowing convenient argument passing

The adapters are declared explicitly at the definition site, making the conversion possibilities visible and controlled.

## Syntax

### Parameter Declaration with Adapters

```
param_with_adapters = ident ":" type "with" adapter_list
adapter_list = qualid {"," qualid}
```

Example:
```jo
def println(s: String with charToStr, intToStr): Unit = ...

def process(data: List[Int] with arrayToList, setToList): Unit = ...
```

### Multiple Parameters

Each parameter can have its own adapters:
```jo
def combine(x: String with intToStr, y: String with boolToStr): String = ...
```

## Semantics

### Type Checking Algorithm

When type-checking a function call `f(arg)` where parameter `p` has type `T` with adapters `[a1, a2, ..., an]`:

1. **Direct conformance check**: If `arg` conforms to `T`, use `arg` directly
2. **Adapter search**: If `arg` does not conform to `T`:
   - For each adapter `ai` in order:
     - Type-check `ai(arg)`
     - If `ai(arg)` has type `T`, transform the call to `f(ai(arg))`
     - If successful, stop searching
   - If no adapter succeeds, report type error

### Adapter Function Requirements

An adapter function must satisfy:

1. **Single parameter**: The adapter must take exactly one argument
2. **Return type**: The adapter must return the parameter's declared type (or a subtype)
3. **No auto parameters**: The adapter may not have auto parameters
4. **Context parameters allowed**: The adapter may have context parameters

Example valid adapters:
```jo
def intToStr(x: Int): String = ...                    // Valid
def showInt(x: Int): String receives printer = ...    // Valid (context params OK)

def badAdapter(x: Int, y: Int): String = ...          // Invalid (multiple params)
def badAdapter2(x: Int)(auto show: Show[Int]): String = ...  // Invalid (auto params)
def badAdapter3[T](x: T)(auto show: Show[T]): String = ...   // Invalid (type params)
```

### Adapter Selection

Adapters are tried in declaration order. The first adapter that successfully type-checks is used.

```jo
def example(s: String with adapter1, adapter2): Unit = ...

// If both adapter1 and adapter2 could convert the argument,
// adapter1 is always tried first
```

**Note:** Adapter selection is based solely on the adapter's parameter type matching the argument type as call-site. Context parameters of adapters do not affect adapter selection.

### Adapters with Varargs

When a varargs parameter has adapters, the adapters apply to individual elements, not to the entire sequence.

```jo
def intToStr(i: Int): String = intToString(i)

def printAll(items: ..String with intToStr): Unit = ...

// Usage
printAll("a", "b", "c")  // Direct: all arguments are String
printAll(1, 2, 3)        // Each Int is transformed: printAll(intToStr(1), intToStr(2), intToStr(3))
printAll("a", 42, "c")   // Mixed: printAll("a", intToStr(42), "c")
```

## Restrictions

### Auto Parameters Cannot Have Adapters

Auto parameters may not declare adapters. Auto resolution and param adaption should not interfere to avoid complexity in compiler as well as harm to readability.

### No Polymorphic Adapters

Adapters cannot be polymorphic functions, and parameters with polymorphic types cannot declare adapters.

**Adapters cannot have type parameters:**

```jo
// Invalid - adapter cannot be polymorphic
def genericAdapter[T](x: T): String = ...

def process(s: String with genericAdapter): Unit = ...  // Error
```

**Parameters with type variables cannot have adapters:**

```jo
// Invalid - parameter type contains type parameter
def process[T](items: List[T] with arrayToList, setToList): Unit = ...  // Error
```

**Rationale:**

This constraint simplifies the type checking algorithm and prevents ambiguity in type inference:

1. **Simplicity**: No need to infer type arguments for adapter functions
2. **Predictability**: The adapter choice is clear and doesn't depend on type inference
3. **Explicitness**: Users must provide concrete conversions for concrete types

**Future considerations:**

While this constraint may be relaxed in the future, current use cases don't clearly justify the added complexity. For example:

```jo
// Potential future feature (currently disallowed)
def arrayToList[T](arr: Array[T]): List[T] = ...
def setToList[T](s: Set[T]): List[T] = ...

def process[T](items: List[T] with arrayToList, setToList): Unit = ...
```

Generic data structure conversions like these might be valuable, but the interaction with type inference and the potential for unexpected behavior requires careful design. The current restriction prioritizes simplicity and predictability.

### No Recursive Adapter Application

Adapters are applied at most once. The compiler does not chain adapters in two ways:

**1. No sequential chaining**: Multiple adapters are not composed together.

```jo
def intToBool(x: Int): Bool = x != 0
def boolToStr(b: Bool): String = if b then "true" else "false"

def process(s: String with intToBool, boolToStr): Unit = ...

process(5)  // Error: intToBool(5) returns Bool, not String
            // boolToStr not chained after intToBool
```

**2. No nested adapter application**: When calling an adapter function, adapters on the adapter's parameters are not applied.

```jo
def innerAdapter(x: Char): Int = ...
def outerAdapter(x: Int with innerAdapter): String = ...

def process(s: String with outerAdapter): Unit = ...

process('x')  // Error: outerAdapter('x') fails
              // innerAdapter is NOT applied to convert 'x' to Int
              // Only direct argument type is checked for adapter parameters
```

This restriction prevents deep chains of conversions that harm code readability. It may be relaxed in the future if clear use cases emerge, but the current design prioritizes explicitness and predictability.

## Examples

### Basic Adapter Usage

```jo
def charToStr(c: Char): String = charToString(c)
def intToStr(i: Int): String = intToString(i)
def boolToStr(b: Bool): String = if b then "true" else "false"

def println(s: String with charToStr, intToStr, boolToStr): Unit =
  // implementation

// Usage
println("hello")   // Direct: String conforms to String
println('x')       // Transformed to: println(charToStr('x'))
println(42)        // Transformed to: println(intToStr(42))
println(true)      // Transformed to: println(boolToStr(true))
```

### Multiple Parameters with Adapters

```jo
def formatPair(x: String with intToStr, y: String with boolToStr): String =
  "x=" + x + ", y=" + y

formatPair(42, true)
// Transformed to: formatPair(intToStr(42), boolToStr(true))
```

### Adapters with Context Parameters

```jo
param hexMode: Bool = false

// Adapter that converts Int to String based on hexMode context parameter
def intToStr(n: Int): String receives hexMode =
  if hexMode then
    "0x" + intToHexString(n)
  else
    intToString(n)

def display(msg: String with intToStr): Unit =
  println(msg)

// Usage with default decimal format
display(42)        // Transformed to: display(intToStr(42))
                   // Outputs: "42" (decimal, hexMode = false)

// Usage with custom hex format
display(255) with hexMode = true
                   // Transformed to: display(intToStr(255))
                   // Outputs: "0xff" (hexadecimal, hexMode = true)
```

This example demonstrates how adapters can use context parameters to provide context-dependent conversion. The `intToStr` adapter accesses the `hexMode` context parameter to determine whether to format numbers as decimal or hexadecimal.

## Type Error Messages

When no adapter succeeds, the error message should indicate:

1. The expected parameter type
2. The actual argument type
3. The adapters that were tried

Example error message:
```
---------- Error at example.jo:10:8 ---------------
| process(myList)
|         ^^^^^^
| Type mismatch for parameter s: String
|   Found: List[Int]
|   Tried adapters: intToStr, boolToStr (none applicable)
```

## Implementation Notes

### Type Checking Phase

During type checking in `Namer.scala`:

1. When typing a function application, check if the argument conforms to the parameter type
2. If not, retrieve the parameter's adapter list
3. For each adapter:
   - Resolve the adapter name to a symbol
   - Check adapter validity (single param, no auto params, correct return type)
   - Try typing the adapter application
   - If successful, transform the tree to include the adapter call
4. If all adapters fail, report a comprehensive error

### Adapter Validation

When declaring a function with parameter adapters:

1. Verify each adapter refers to a valid function
2. Check adapter function signature constraints

## Future Extensions

Potential future enhancements (not part of initial design):

1. **Context-dependent adapters**: Allow adapters to depend on auto parameters
2. **Adapter composition**: Allow limited chaining of adapters
3. **Adapter groups**: Define reusable sets of adapters
4. **Pattern adapter sugar**: Apply adapters in certain pattern contexts

## Design Decisions

### Why Definition-Site, Not Call-Site?

Adapters are declared at the function definition, not the call site, because:

1. **Discoverability**: Users can see what conversions are allowed when reading the function signature
2. **Control**: Function authors control what conversions are acceptable for their function
3. **Simplicity**: No ambiguity about which adapters apply

### Why Not Implicit Conversions?

Unlike implicit conversions in languages like Scala:

1. **Explicit**: Adapters are visible in the function signature
2. **Scoped**: Adapters only apply to specific parameters, not globally
3. **Ordered**: No ambiguity about which adapter is chosen (first match wins)
4. **Limited**: Adapters don't chain, preventing unexpected cascading conversions

### Why Not Overloading?

Compared to function overloading:

1. **Simpler**: No complex overload resolution rules
2. **Clearer**: The conversion functions are explicitly named
3. **Flexible**: Adapters can be functions with context parameters
4. **Maintainable**: Adding a new conversion doesn't require defining a new overload

## Summary

Parameter adapters provide a lightweight, explicit mechanism for argument conversion that:

- Maintains code readability through explicit adapter declarations
- Avoids the complexity of overloading resolution
- Prevents the pitfalls of implicit conversions
