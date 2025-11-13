# Auto Parameters

## Overview

Auto parameters provide a controlled form of automatic argument resolution at function call sites. When an auto parameter is not explicitly provided, the compiler searches through a declared list of candidate values to find one that matches the required type.

**Two forms of candidates:**

- **Value candidates** (`eqInt`, `eqString`): Named values to search
- **Member candidates** (`[T].==`): The member on a type `T` might satisfy the auto type

## Motivation

Auto parameters eliminate repetitive argument passing for configuration values, type class instances, and capabilities while maintaining explicit control over the search space:

```jo
// Without auto parameters
def Set[T](l: ..T, eq: Eq[T]): Set[T] = ...
val intSet = Set(1, 2, 3, eqInt)
val strSet = Set("a", "b", eqString)

// With auto parameters
def Set[T](l: ..T)(auto eq: Eq[T] in [eqInt, eqString]): Set[T] = ...
val intSet = Set(1, 2, 3)
val strSet = Set("a", "b")
```

### Key Design Principle: Explicitness

Unlike Scala implicits, Jo's auto parameters prioritize readability by making the candidate list explicit in the function signature. This ensures that readers can always see where auto values come from without understanding complex scope rules.

## Syntax

### Definition Site

```
auto_param = "auto" ident ":" type "in" "[" candidate_list "]"
candidate_list = candidate {"," candidate}
candidate = qualid | "[" type "]" "." ident
```

**Value candidate:** Named value (qualified identifier)
**Member candidate:** Type in brackets + dot + member name (`[Int].default`, `[T].eq`)

### Call Site

```
call_with_having = call ["having" binding_list]
binding_list = binding {"," binding}
binding = ident "=" expr
```

The `having` keyword immediately follows a call expression to explicitly provide auto arguments.

### Examples

```jo
// Value candidates
def compare(a: T, b: T)(auto eq: Eq[T] in [eqInt, eqString]): Bool = ...

// Member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] in [[T].==]): Bool = ...

// Mixed candidates
def process[T](x: T, y: T)(auto eq: Eq[T] in [eqInt, [T].==]): Bool = ...

// Multiple auto parameters
def sort[T](xs: List[T])(auto eq: Eq[T] in [[T].==], auto ord: Ord[T] in [[T].compare]): List[T] = ...

// Explicit provision at call site
process(x, y) having eq = customEq
sort(xs) having eq = customEq, ord = customOrd
```

## Semantics

### Search Algorithm

For call `f(arg)` where there's an auto parameter `auto x: T in [c1, c2, ..., cn]`:

1. **Check explicit provision:** If caller provides `f(arg) having x = value`, use `value` directly
2. **Search candidates in order:**
    - **Value candidate** `ci` qualify if`ci` conforms to `T`
    - **Member candidate** `[U].member` qualify if `(a: U, x_i: X_i) => a.member(x_i)` has the type `T`, where `x: X` is the parameter type(s) of `member`.
3. **First match wins:** Stop after first successful candidate
4. **No match:** Report error that no suitable auto value was found

### Value Candidates

**Requirements:**

- Must be a named value or function without regular parameters
- Type must match the auto parameter's type

**Allowed:**

- Can have auto parameters themselves (nesting allowed)
- Can have context parameters

**Examples:**
```jo
def eqInt: Eq[Int] = (a, b) => a == b                    // Valid
def eqList[T](auto eqItem: Eq[T] in [eqInt]): Eq[List[T]] = ...  // Valid (nested auto)
def showInt: String receives formatter = ...              // Valid (context OK)
```

**Resolution with nesting:**
```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqList[T](auto eqItem: Eq[T] in [eqInt]): Eq[List[T]] = (as, bs) => ...

def process[T](xs: List[T])(auto eq: Eq[List[T]] in [eqList]): Unit = ...

process([1, 2, 3])   // Tries eqInt (no match), tries eqList[Int] → searches for Eq[Int] → finds eqInt ✓
```

### Member Candidates

**Syntax:** `[T].member` where `T` is a type (possibly a type parameter)

**Semantics:** Eta-expand the member into a function where the receiver becomes the first parameter.

For a method `def member(params): ReturnType` on type `T`, the eta-expansion is:
```
(receiver: T, params) => receiver.member(params)
```

This eta-expanded function must conform to the auto parameter type.

**Requirements:**

- Member must exist on type `T`
- The eta-expanded function type must match the auto parameter type
- Auto and context parameters in the member are propagated to the eta-expanded function

**Examples:**
```jo
// Basic member candidate
type Eq[T] = (T, T) => Bool

class Int
  def ==(that: Int): Bool = ...
end

def process[T](x: T, y: T)(auto eq: Eq[T] in [[T].==]): Bool = eq(x, y)

// For T=Int:
// [Int].== refers to Int.==
// Eta-expansion: (receiver: Int, that: Int) => receiver == that
// Type: (Int, Int) => Bool which is Eq[Int] ✓

process(42, 43)  // Uses eta-expanded Int.==
```

**Member candidate with auto parameter:**
```jo
class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] in [[T].==]): Bool = ...
end

def process[T](xs: List[T], ys: List[T])(auto eq: Eq[List[T]] in [[List[T]].==]): Bool = eq(xs, ys)

// For T=Int:
// [List[Int]].== refers to List[Int].==
// Eta-expansion: (receiver: List[Int], that: List[Int])(auto eqItem: Eq[Int] in [[Int].==]) => receiver == that
// Type: (List[Int], List[Int])(auto Eq[Int] in [[Int].==]) => Bool which is Eq[List[Int]] ✓
// The nested auto searches for Eq[Int], finds [Int].==, eta-expands to Eq[Int] ✓

process([1, 2], [1, 2])  // Uses eta-expanded List[Int].==, which uses eta-expanded Int.==
```

**Type instantiation:** When `T` is a type parameter, it's instantiated based on the call site. For `[List[T]].==`, the entire type expression `List[T]` (with `T` instantiated) is used to look up the member.

### Candidate Order

Candidates are tried sequentially. First match wins.

```jo
def eqInt: Eq[Int] = ...
def eqAny[T]: Eq[T] = ...  // Reference equality

def process[T](x: T)(auto eq: Eq[T] in [eqInt, eqAny]): Unit = ...

process(42)      // Tries eqInt ✓ (stops, doesn't try eqAny)
process("hi")    // Tries eqInt ✗ (Int != String), tries eqAny[String] ✓
```

### Explicit Provision with `having`

Callers can explicitly provide auto arguments using the `having` keyword:

```jo
def process[T](x: T)(auto eq: Eq[T] in [eqInt, eqString]): Unit = ...

process(42)                    // Uses eqInt (from candidates)
process(42) having eq = customEq   // Uses customEq (explicit)
```

**Syntax rules:**

- `having` must immediately follow a call expression
- Multiple bindings separated by commas
- Binding names must match auto parameter names
- Each auto parameter can be provided at most once

**Examples:**
```jo
// Multiple auto parameters
def compare[T](a: T, b: T)(auto eq: Eq[T] in [eqInt], auto ord: Ord[T] in [ordInt]): Int = ...

compare(1, 2)                          // Both from candidates
compare(1, 2) having eq = customEq     // Override eq only
compare(1, 2) having eq = customEq, ord = customOrd  // Override both
```

## Validation Rules

### Shadowed Candidates

Later candidates must not be shadowed by earlier candidates.

**Value candidate shadowing value candidate:**

For value candidates `[..., ci, ..., cj, ...]` where `j > i`, the type of `cj` must not be a subtype of the type of `ci` when both are applicable.

```jo
def eqInt: Eq[Int] = ...
def anotherEqInt: Eq[Int] = ...

// Error: anotherEqInt shadowed by eqInt
def foo[T](auto eq: Eq[T] in [eqInt, anotherEqInt]): Unit = ...
```

**Valid - Distinct types:**
```jo
def eqInt: Eq[Int] = ...
def eqString: Eq[String] = ...

def foo[T](auto eq: Eq[T] in [eqInt, eqString]): Unit = ...  // OK
```

**Valid - Specific before general with type parameters:**
```jo
def eqList[T](auto eq: Eq[T] in [eqInt]): Eq[List[T]] = ...
def eqAny[T]: Eq[T] = ...  // Reference equality

// OK: eqList is more specific for List types, eqAny is fallback
def process[T](x: T)(auto eq: Eq[T] in [eqList, eqAny]): Unit = ...
```

**Member candidate shadowing member candidate:**

Member candidates with the same member name are redundant.

```jo
// Error: [T].== appears twice
def foo[T](auto eq: Eq[T] in [[T].==, [T].==]): Unit = ...
```

**Member candidate shadowing value candidate:**

A member candidate shadows a later value candidate if the eta-expanded member could produce the value's type for some type instantiation.

```jo
def eqInt: Eq[Int] = ...

// Error: For T=Int, [T].== eta-expands to (Int, Int) => Bool which is Eq[Int], shadowing eqInt
def foo[T](x: T)(auto eq: Eq[T] in [[T].==, eqInt]): Unit = ...
  where Int has def ==(that: Int): Bool
```

**Value candidate before member candidate (OK):**

Value candidates don't shadow member candidates. The value candidate handles a closed type set, while the member candidate handles an open type set (all types with the member), so the member candidate remains reachable for other types.

```jo
def eqInt: Eq[Int] = ...

// OK: eqInt handles Eq[Int], [T].== handles types with == method
def foo[T](x: T)(auto eq: Eq[T] in [eqInt, [T].==]): Unit = ...
```

### Explicit Provision

When using `having` to provide auto arguments:

- Binding names must match declared auto parameter names
- Each auto parameter can be provided at most once
- Provided value must conform to the auto parameter's type
- Cannot provide arguments for non-auto parameters via `having`

```jo
def foo(auto eq: Eq[T] in [eqInt]): Unit = ...

foo having eq = customEq          // OK
foo having eq = 42                // Error: 42 is not Eq[T]
foo having eq = e1, eq = e2       // Error: eq provided twice
foo having notAuto = value        // Error: notAuto is not an auto parameter
```

## Restrictions

### Parameter Adapters

Auto parameters cannot have parameter adapters.

```jo
def foo(auto ctx: Context in [ctx1] with [adapter]): Unit = ...  // Invalid
```

**Rationale:** Auto parameters are for automatic resolution, not argument conversion. Mixing both features would be confusing.

### Polymorphism

Auto parameters with type variables and their candidates must follow polymorphism rules:

- Value candidates can be polymorphic (have type parameters)
- Type parameters are instantiated based on the required type
- Member candidates work with type parameters naturally through type instantiation

```jo
// Valid - polymorphic candidate
def eqList[T](auto eq: Eq[T] in [eqInt]): Eq[List[T]] = ...
def foo[T](auto eq: Eq[T] in [eqList]): Unit = ...

// Valid - member candidate with type parameter
def bar[T](auto eq: Eq[T] in [[T].eq]): Unit = ...
```

### Position

Auto parameters must appear in their own parameter list, after regular parameters.

```jo
// Valid
def foo(x: Int)(auto eq: Eq[T] in [eqInt]): Unit = ...

// Valid - multiple auto parameters in same list
def foo(x: Int)(auto eq: Eq[T] in [eqInt], auto ord: Ord[T] in [ordInt]): Unit = ...

// Invalid - mixed with regular parameters
def foo(x: Int, auto eq: Eq[T] in [eqInt]): Unit = ...
```

**Rationale:** Clear separation between explicit arguments and auto arguments improves readability.

## Examples

### Type Class Pattern

```jo
type Eq[T] = {
  def eq(a: T, b: T): Bool
}

def eqInt: Eq[Int] = (a, b) => a == b
def eqString: Eq[String] = (a, b) => a == b
def eqList[T](auto eqItem: Eq[T] in [eqInt, eqString]): Eq[List[T]] = (as, bs) =>
  if as.size != bs.size then false
  else
    var equal = true
    var i = 0
    while equal && i < as.size do
      equal = eqItem.eq(as[i], bs[i])
      i = i + 1
    equal

// Set implementation using auto parameter
def Set[T](l: ..T)(auto eq: Eq[T] in [eqInt, eqString, eqList]): Set[T] = {
  def contains(x: T): Bool = l.exists(e => eq.eq(e, x))
  def +(x: T): Set[T] = if contains(x) then this else Set(..(l + x))
  // ...
}

// Usage - auto parameters resolved automatically
val intSet = Set(1, 2, 3)           // Uses eqInt
val strSet = Set("a", "b")          // Uses eqString
val listSet = Set([1,2], [3,4])     // Uses eqList[Int], which uses eqInt
```

### Member Candidates for Extensibility

```jo
type Eq[T] = (T, T) => Bool

// Built-in types provide equality via == method
class Int
  def ==(that: Int): Bool = ...
end

class String
  def ==(that: String): Bool = ...
end

class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] in [[T].==]): Bool = ...
end

// Generic function using member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] in [[T].==]): Bool =
  eq(x, y)

// Works for any type with == method
areEqual(1, 2)           // Uses eta-expanded Int.== → (a: Int, b: Int) => a == b
areEqual("a", "b")       // Uses eta-expanded String.== → (a: String, b: String) => a == b
areEqual([1,2], [1,2])   // Uses eta-expanded List[Int].== which uses eta-expanded Int.==
```

### Mixed Candidates

```jo
type Eq[T] = (T, T) => Bool

// Custom equality for special cases, fallback to member
def eqIntMod10: Eq[Int] = (a, b) => (a % 10) == (b % 10)

def compare[T](x: T, y: T)(auto eq: Eq[T] in [eqIntMod10, [T].==]): Bool =
  eq(x, y)

compare(15, 25)         // Uses eqIntMod10 → true (both end in 5)
compare("a", "a")       // eqIntMod10 doesn't match String, uses eta-expanded String.== → true
```

### Explicit Override

```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqIntCaseInsensitive: Eq[Int] = (a, b) => abs(a) == abs(b)

def process(x: Int, y: Int)(auto eq: Eq[Int] in [eqInt]): Bool =
  eq.eq(x, y)

process(1, 1)                              // Uses eqInt → true
process(1, -1)                             // Uses eqInt → false
process(1, -1) having eq = eqIntCaseInsensitive  // Uses override → true
```

### Context Parameters Integration

```jo
param precision: Int = 2

def eqFloat: Eq[Float] receives precision = (a, b) =>
  val threshold = pow(10.0, -precision)
  abs(a - b) < threshold

def compare(x: Float, y: Float)(auto eq: Eq[Float] in [eqFloat]): Bool =
  eq.eq(x, y)

compare(1.001, 1.002)                           // Uses eqFloat with precision=2 → true
compare(1.001, 1.002) with precision = 4        // Uses eqFloat with precision=4 → false
compare(1.001, 1.009) having eq = exactEqFloat  // Uses exact equality
```

## Design Rationale

### Why Explicit Candidate Lists?

Unlike Scala's implicit scope rules, Jo requires explicit candidate lists because:

- **Readability:** Function signatures show exactly where auto values come from
- **Predictability:** No hidden scope rules or import-dependent behavior
- **Simplicity:** No need to understand complex implicit resolution rules
- **Local reasoning:** Can understand function behavior from its signature alone

### Why `in` Instead of `with`?

The keyword `in` clearly distinguishes auto parameters from parameter adapters:

- **`with` (adapters):** Transform/convert the provided argument
- **`in` (autos):** Search for a value in the candidate list

This distinction is important because the semantics are fundamentally different:
- Adapters operate on caller-provided values
- Autos search through predefined candidates

### Why First Match?

First-match semantics (like parameter adapters) provide:

- **Simplicity:** Easy to understand and predict
- **Performance:** No need to check all candidates
- **Control:** Order matters, enabling "specific before general" patterns
- **Consistency:** Same resolution strategy as parameter adapters

### Why Member Candidates?

Member candidates `[T].member` provide extensibility through eta-expansion:

**Eta-expansion mechanism:** A method like `def ==(that: T): Bool` on type `T` is eta-expanded to `(receiver: T, that: T) => receiver == that`, which has type `(T, T) => Bool` (i.e., `Eq[T]`).

**Benefits:**

- **Type-directed:** Types can provide their own auto values by defining methods
- **Generic code:** Functions work with any type that has the appropriate member
- **No modification needed:** Types can participate in auto resolution without library changes
- **Consistent design:** Similar to member adapters (`.toString`), but for auto parameters
- **Composability:** Members with auto parameters enable recursive resolution (e.g., `List[T].==` uses `[T].==`)

**Example:**
```jo
// Types define == method
class Int
  def ==(that: Int): Bool = ...
end

// Generic function uses member candidate
def areEqual[T](x: T, y: T)(auto eq: Eq[T] in [[T].==]): Bool = eq(x, y)

// Eta-expansion: Int.== → (a: Int, b: Int) => a == b → Eq[Int]
areEqual(1, 2)  // Works automatically
```

### Why No Global `auto def`?

The old `auto def` feature had Scala-like problems:

- Values were globally available, making it hard to track where they come from
- Required understanding global scope rules
- Made code less readable and predictable

The new design requires explicit candidate lists, making the code self-documenting.

### Comparison with Scala Implicits

| Feature | Scala Implicits | Jo Auto Parameters |
|---------|----------------|-------------------|
| Candidate visibility | Implicit scope (complex rules) | Explicit list in signature |
| Search space | Global + imports | Declared candidates only |
| Resolution | Best match (complex) | First match (simple) |
| Readability | Hidden, hard to trace | Visible in signature |
| Call site override | via implicit parameters | via `having` keyword |
| Extensibility | Implicit definitions anywhere | Member candidates |

Jo's design prioritizes explicitness and readability over convenience, making code easier to understand and maintain.
