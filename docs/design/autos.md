# Auto Parameters

## Overview

Auto parameters provide a controlled form of automatic argument resolution at function call sites. When an auto parameter is not explicitly provided, the compiler searches through a declared list of candidate values to find one that matches the required type.

**Two forms of candidates:**

- **Value candidates** (`eqInt`, `eqString`): Named values to search
- **Member candidates** (`[T].==`): The member on a type `T` might satisfy the auto type

## Motivation

Auto parameters eliminate repetitive argument passing for configuration values and type class instances while maintaining explicit control over the search space:

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

### Key Design Principles

**1. Explicitness:**

Unlike Scala implicits, Jo's auto parameters prioritize readability by making the candidate list explicit in the function signature. Auto values come from only two sources:

- The `having` clause at call sites (local, explicit)
- Declared candidate lists in signatures (visible, explicit)

**No global or implicit search scope** - everything is traceable.

**2. Local search scope:**

The `having` clause provides a **local search scope** that takes priority over all candidate lists, including for nested auto resolution. When resolving any auto parameter (direct or nested), the compiler:

1. First checks the `having` clause
2. Only if not found, searches the declared candidate list

This makes `having` a powerful but explicit way to control auto resolution locally, without introducing global scope rules.

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
binding = type "=" expr
```

The `having` keyword immediately follows a call expression to explicitly provide auto arguments **by type**, not by name.

### Examples

```jo
// Value candidates
def compare(a: T, b: T)(auto eq: Eq[T] in [eqInt, eqString]): Bool = ...

// Member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] in [[T].==]): Bool = ...

// Mixed candidates
def process[T](x: T, y: T)(auto eq: Eq[T] in [eqInt, [T].==]): Bool = ...

// Multiple auto parameters
def sort[T](xs: List[T])(auto eq: Eq[T] in [[T].==], ord: Ord[T] in [[T].compare]): List[T] = ...

// Explicit provision at call site (by type)
process(x, y) having Eq[Int] = customEq
sort(xs) having Eq[String] = customEq, Ord[String] = customOrd
```

## Semantics

### Search Algorithm

For call `f(arg) having T1 = v1, T2 = v2` where there's an auto parameter `auto x: T in [c1, c2, ..., cn]`:

1. **Check having clause first:** If `having` provides a binding `T = value` where `T` matches the auto parameter's type, use `value` directly
    - The type `T` in the `having` clause is matched against the auto parameter's type
    - If multiple auto parameters have the same type, they all receive the same provided value
2. **Search candidates in order (if not found in having):**
    - **Value candidate** `ci` qualifies if `ci` conforms to `T`
    - **Member candidate** `[U].member` qualifies if `(a: U, x_i: X_i) => a.member(x_i)` has the type `T`, where `x: X` is the parameter type(s) of `member`.
    - **When a candidate has auto parameters itself**, resolve those nested autos recursively using the same algorithm (checking `having` first, then the nested candidate list)
3. **First match wins:** Stop after first successful candidate
4. **No match:** Report error that no suitable auto value was found

**Critical: `having` provides local search scope**

The `having` clause establishes a **local search scope** that takes priority over all candidate lists. When resolving nested auto requirements:

1. First check the `having` clause for a matching type
2. Only if not found in `having`, search the candidate list

This ensures **no global or magic search scope** - all auto resolution is either from explicit `having` bindings or from declared candidate lists.

### Value Candidates

**Requirements:**

- Must be a named value or function without regular parameters
- Type must match the auto parameter's type (subtyping allowed)
- Cannot directly or indirectly reference the function being defined (to ensure termination)

**Allowed:**

- Can have auto parameters themselves (nesting allowed)
- Can have context parameters

**Rationale for "no regular parameters":** This avoids ambiguity about partial application. If you need parameterized candidates, wrap them in non-parameterized definitions.

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

**Member candidates with auto parameters:** When a member candidate itself has auto parameters (like `List[T].==` having `auto eqItem: Eq[T]`), those nested autos are resolved using the same algorithm:

1. Check the `having` clause from the original call site (local scope!)
2. If not found, search the nested auto parameter's candidate list

This enables compositional type class instances: `List[Int].==` automatically finds `Int.==` for element comparison, and callers can override with `having Eq[Int] = customEq`.

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

Callers can explicitly provide auto arguments using the `having` keyword **by type**:

```jo
def process[T](x: T)(auto eq: Eq[T] in [eqInt, eqString]): Unit = ...

process(42)                        // Uses eqInt (from candidates)
process(42) having Eq[Int] = customEq   // Uses customEq (explicit)
```

**Syntax rules:**

- `having` must immediately follow a call expression
- Multiple bindings separated by commas
- Bindings specify **types**, not parameter names
- Each type can be provided at most once
- If multiple auto parameters have the same type, they all receive the same provided value

**Examples:**
```jo
// Multiple auto parameters
def compare[T](a: T, b: T)(auto eq: Eq[T] in [eqInt], ord: Ord[T] in [ordInt]): Int = ...

compare(1, 2)                                    // Both from candidates
compare(1, 2) having Eq[Int] = customEq          // Override eq only
compare(1, 2) having Eq[Int] = customEq, Ord[Int] = customOrd  // Override both
```

**Nested auto resolution with local search scope:**

The key advantage of type-based `having` is providing a local search scope for nested auto requirements. The `having` clause values are tried **first** before searching candidate lists:

```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqList[T](auto eqItem: Eq[T] in [eqInt]): Eq[List[T]] = ...

def process[T](xs: List[T])(auto eq: Eq[List[T]] in [eqList]): Unit = ...

// Example 1: No having clause
process([1, 2])
// Resolution:
// 1. Need Eq[List[Int]], search candidates: finds eqList[Int]
// 2. eqList[Int] needs Eq[Int], search its candidates: finds eqInt ✓

// Example 2: Provide nested requirement via having
def customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

process([1, 2]) having Eq[Int] = customIntEq
// Resolution:
// 1. Need Eq[List[Int]], check having: not found, search candidates: finds eqList[Int]
// 2. eqList[Int] needs Eq[Int], check having: finds customIntEq ✓
// The having clause provides customIntEq for the nested requirement!

// Example 3: Override both outer and nested
def customListEq: Eq[List[Int]] = (a, b) => a.size == b.size

process([1, 2]) having Eq[List[Int]] = customListEq, Eq[Int] = customIntEq
// Resolution:
// 1. Need Eq[List[Int]], check having: finds customListEq ✓
// (Eq[Int] binding is available but not needed since customListEq is used directly)
```

**Key insight:** The `having` clause creates a local search scope that shadows candidate lists for **all nested auto resolution**, not just the immediate call. This maintains explicitness - no global search, just locally-scoped overrides.

**Propagation through calls:** The `having` clause propagates through the entire call chain initiated by the call. When a function called during auto resolution (e.g., a candidate with nested autos) needs to resolve its own auto parameters, it checks the same `having` clause from the original call site. This enables deep override of nested requirements.

**Same type in multiple parameters:**

When multiple auto parameters have the same type, one `having` binding satisfies all of them:

```jo
def compare[T](a: T, b: T)(auto eq1: Eq[T] in [eqInt], eq2: Eq[T] in [eqInt]): Bool = ...

// Both eq1 and eq2 receive customEq
compare(1, 2) having Eq[Int] = customEq
```

**Design note:** This is a deliberate choice—`having` provides values **by type**, not by parameter name. If you need different instances for different purposes, use distinct types (e.g., `StrictEq[T]` vs `FuzzyEq[T]`). This maintains the type-directed nature of auto resolution and prevents having clauses from becoming overly verbose.

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

A member candidate `[U].member` shadows a later value candidate if the eta-expanded member could produce the value's type for some type instantiation of the type parameters.

```jo
def eqInt: Eq[Int] = ...

// Error: For T=Int, [T].== eta-expands to (Int, Int) => Bool which is Eq[Int], shadowing eqInt
def foo[T](x: T)(auto eq: Eq[T] in [[T].==, eqInt]): Unit = ...
  where Int has def ==(that: Int): Bool
```

**Shadowing detection:** The compiler checks if there exists any type instantiation where the member candidate's eta-expanded type matches a later value candidate's type. This is conservative: `[T].member` shadows `candidateValue: C[X]` if substituting type parameters could make them equal.

**Example - detected at definition site:**
```jo
// Error: [T].== could produce Eq[Int] (when T=Int), shadowing eqInt
def bar[T](auto eq: Eq[T] in [[T].==, eqInt]): Unit = ...

// OK: eqInt comes first, so [T].== doesn't shadow it
def baz[T](auto eq: Eq[T] in [eqInt, [T].==]): Unit = ...
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

- Bindings specify **types**, not parameter names
- Each type can be provided at most once in the `having` clause
- Provided value must conform to the specified type
- The type must match at least one auto parameter's type (direct or nested)

```jo
def foo[T](auto eq: Eq[T] in [eqInt]): Unit = ...

foo having Eq[Int] = customEq              // OK
foo having Eq[Int] = 42                    // Error: 42 does not conform to Eq[Int]
foo having Eq[Int] = e1, Eq[Int] = e2      // Error: Eq[Int] provided twice
foo having String = "x"                    // Error: No auto parameter needs String
```

**Type matching:**

The type in the `having` clause must match the auto parameter's type **after type parameter instantiation**.

```jo
def process[T](xs: List[T])(auto eq: Eq[List[T]] in [eqList]): Unit = ...

process([1, 2]) having Eq[List[Int]] = customEq  // OK: T=Int, so Eq[List[T]] = Eq[List[Int]] ✓
process([1, 2]) having Eq[Int] = customEq        // OK if eqList has nested auto needing Eq[Int]
                                                 // Error otherwise: Eq[Int] doesn't match Eq[List[Int]]
```

**Resolution order:** Type inference happens first (determining type parameters), then auto resolution searches for values matching the instantiated types.

### Coherence

Auto parameters provide **per-call-site coherence**, not global coherence:

**Within a single function call:** All uses of the same auto parameter receive the same resolved value. This ensures consistency within one operation.

```jo
def process[T](xs: List[T])(auto eq: Eq[T] in [eqInt]): Unit =
  val a = helper1(xs)  // helper1 needs Eq[T], gets eqInt
  val b = helper2(xs)  // helper2 needs Eq[T], gets same eqInt instance
  // Coherent within this call
```

**Across different function calls:** Different calls can use different instances, especially with `having`:

```jo
process([1, 2])                      // Uses eqInt from candidates
process([1, 2]) having Eq[Int] = customEq  // Uses customEq from having

// These two calls use different Eq[Int] instances - this is intentional!
// The having clause makes the difference explicit.
```

**Design rationale:** This is a feature, not a bug. Jo prioritizes **explicit control** over **global consistency**. The `having` clause makes non-uniform behavior visible at call sites, maintaining readability.

**Contrast with Haskell:** Haskell type classes enforce global coherence—only one instance per type exists globally. Jo's approach is more flexible, allowing context-specific behavior while keeping it explicit.

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
def foo(x: Int)(auto eq: Eq[T] in [eqInt], ord: Ord[T] in [ordInt]): Unit = ...

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
def eqIntAbsEqual: Eq[Int] = (a, b) => abs(a) == abs(b)

def process(x: Int, y: Int)(auto eq: Eq[Int] in [eqInt]): Bool =
  eq(x, y)

process(1, 1)                                  // Uses eqInt → true
process(1, -1)                                 // Uses eqInt → false
process(1, -1) having Eq[Int] = eqIntAbsEqual  // Uses override → true
```

### Context Parameters Integration

```jo
param precision: Int = 2

def eqFloat: Eq[Float] receives precision = (a, b) =>
  val threshold = pow(10.0, -precision)
  abs(a - b) < threshold

def compare(x: Float, y: Float)(auto eq: Eq[Float] in [eqFloat]): Bool =
  eq(x, y)

compare(1.001, 1.002)                                // Uses eqFloat with precision=2 → true
compare(1.001, 1.002) with precision = 4             // Uses eqFloat with precision=4 → false
compare(1.001, 1.009) having Eq[Float] = exactEqFloat  // Uses exact equality
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

### Why Type-Based `having`?

The `having` clause uses **type-based binding** (`Eq[T] = value`) instead of **name-based binding** (`eq = value`) for several reasons:

**1. Local search scope for nested auto resolution:**

The `having` clause provides a **local search scope** that takes priority over all candidate lists, including for nested auto requirements. This is the key feature that enables fine-grained control:

```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqList[T](auto eqItem: Eq[T] in [eqInt]): Eq[List[T]] = ...
def process[T](xs: List[T])(auto eq: Eq[List[T]] in [eqList]): Unit = ...

def customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

// The having clause provides Eq[Int] for the nested requirement
process([1, 2]) having Eq[Int] = customIntEq
// Resolution: eqList needs Eq[Int] → checks having first → finds customIntEq ✓
```

With name-based binding, we could only override `eq`, but not reach into nested requirements like `eqItem`. Type-based binding makes `having` act as a **local search scope** that affects all nested resolution.

**2. Type-directed nature:**

Auto parameters are fundamentally about type-directed resolution. Using types in the `having` clause aligns with this principle:

- Auto resolution searches by type (first in `having`, then in candidates)
- Provision should also work by type
- Maintains consistency: types everywhere, no name-based lookup

**3. Parameter name independence:**

Callers don't need to know parameter names, only types:

```jo
// Don't need to know the parameter is named "eq"
process(x, y) having Eq[Int] = customEq
```

**4. Shared instances:**

When multiple auto parameters have the same type, one binding satisfies all:

```jo
def foo[T](x: List[T])(auto eq1: Eq[T] in [...], eq2: Eq[T] in [...]): Unit = ...
foo(x) having Eq[Int] = sharedEq  // Both eq1 and eq2 use sharedEq
```

This is often desirable and saves repetition.

**5. No global search scope:**

The design maintains explicitness: auto values come from either:

1. The `having` clause (local scope)
2. Declared candidate lists (explicit in signature)

There is **no global or implicit search scope** - everything is either locally provided or explicitly declared.

### Type Safety

Auto parameters are fully type-safe:

1. **Candidates are type-checked at definition site:** Each candidate in `in [...]` must type-check and conform to the auto parameter's type.

2. **Resolution preserves types:** The search algorithm only selects candidates whose type matches the required type (via conformance/subtyping).

3. **Having clause is type-checked:** Values provided via `having` are type-checked against the auto parameter type after type parameter instantiation.

4. **Eta-expansion is type-preserving:** Member candidates `[T].member` are eta-expanded using standard type rules, preserving soundness.

5. **Termination is guaranteed:** The restriction that candidates cannot reference the defining function prevents infinite recursion during resolution.

**Therefore:** Auto resolution introduces no runtime type errors. All type checking happens at compile time.

### Implementation Complexity

The auto resolution algorithm is straightforward to implement:

**Time complexity:** O(candidates × nesting depth) per auto parameter. Since candidate lists are typically small (2-5 items) and nesting depth is usually shallow (1-3 levels), this is efficient in practice.

**Space complexity:** The `having` clause environment needs to be threaded through auto resolution, but this is a simple map from types to values.

**Separate compilation:** Auto parameters are signature-visible, so they appear in compiled module interfaces. Member candidate resolution requires member lookup on types, which is already part of normal compilation.

**Error recovery:** When auto resolution fails, the compiler has enough context to provide helpful error messages showing which candidates were tried and why they failed.

### Comparison with Scala Implicits

| Feature | Scala Implicits | Jo Auto Parameters |
|---------|----------------|-------------------|
| Candidate visibility | Implicit scope (complex rules) | Explicit list in signature |
| Search space | Global + imports | Declared candidates only |
| Resolution | Best match (complex) | First match (simple) |
| Readability | Hidden, hard to trace | Visible in signature |
| Call site override | via implicit parameters | via `having` keyword |
| Extensibility | Implicit definitions anywhere | Member candidates |
| Coherence | Global | Per-call-site |

Jo's design prioritizes explicitness and readability over convenience, making code easier to understand and maintain.

## Complete Example: Nested Auto Resolution with Local Search Scope

This example demonstrates how `having` provides a **local search scope** that takes priority over candidate lists at all nesting levels:

```jo
type Eq[T] = (T, T) => Bool

// Basic equality implementations
class Int
  def ==(that: Int): Bool = ...  // Standard equality
end

class String
  def ==(that: String): Bool = ...  // Standard equality
end

// List equality requires element equality (nested auto)
class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] in [[T].==]): Bool =
    if this.size != that.size then false
    else
      var i = 0
      var equal = true
      while equal && i < this.size do
        equal = eqItem(this[i], that[i])  // Uses auto eqItem
        i = i + 1
      equal
end

// Set implementation using auto parameters
def Set[T](l: ..T)(auto eq: Eq[T] in [[T].==]): Set[T] = {
  def contains(x: T): Bool = l.exists(e => eq(e, x))
  def +(x: T): Set[T] = if contains(x) then this else Set(..(l + x))
  // ...
}

// ============================================================================
// EXAMPLE 1: Default resolution (no having clause)
// ============================================================================
val intSet = Set(1, 2, 3)
// Resolution for auto eq: Eq[Int]
// 1. Check having: none
// 2. Search candidates [Int].==: eta-expand to (Int, Int) => Bool ✓

val listSet = Set([1,2], [3,4])
// Resolution for auto eq: Eq[List[Int]]
// 1. Check having: none
// 2. Search candidates [List[Int]].==: eta-expand to (List[Int], List[Int])(auto Eq[Int]) => Bool ✓
//    Nested resolution for auto eqItem: Eq[Int]
//    1. Check having: none
//    2. Search nested candidates [Int].==: eta-expand to (Int, Int) => Bool ✓

// ============================================================================
// EXAMPLE 2: Override nested requirement with having (LOCAL SEARCH SCOPE!)
// ============================================================================
def eqStringCaseInsensitive: Eq[String] = (a, b) =>
  a.toLowerCase == b.toLowerCase

val ciListSet = Set(["Hello"], ["HELLO"])
  having Eq[String] = eqStringCaseInsensitive

// Resolution for auto eq: Eq[List[String]]
// 1. Check having for Eq[List[String]]: not found
// 2. Search candidates [List[String]].==: eta-expand to (List[String], List[String])(auto Eq[String]) => Bool ✓
//    Nested resolution for auto eqItem: Eq[String]
//    1. Check having for Eq[String]: found eqStringCaseInsensitive ✓ (LOCAL SCOPE!)
//    (Never searches [String].== because having takes priority)

// Result: ["Hello"] == ["HELLO"] returns true (case-insensitive)
// The having clause provided a LOCAL SEARCH SCOPE for nested auto resolution!

// ============================================================================
// EXAMPLE 3: Override both outer and nested
// ============================================================================
def eqListBySize: Eq[List[String]] = (a, b) => a.size == b.size

val customSet = Set(["a", "b"], ["c", "d"])
  having Eq[List[String]] = eqListBySize, Eq[String] = eqStringCaseInsensitive

// Resolution for auto eq: Eq[List[String]]
// 1. Check having for Eq[List[String]]: found eqListBySize ✓ (LOCAL SCOPE!)
// (Eq[String] binding is provided but not used since eqListBySize is used directly)

// ============================================================================
// EXAMPLE 4: Deep nesting - having affects all levels
// ============================================================================
val nestedSet = Set([[1, 2], [3, 4]], [[5, 6]])

// Without having:
// Needs Eq[List[List[Int]]]
//   → [List[List[Int]]].== needs Eq[List[Int]]
//     → [List[Int]].== needs Eq[Int]
//       → [Int].== provides Eq[Int]

def eqIntMod10: Eq[Int] = (a, b) => (a % 10) == (b % 10)

val customNestedSet = Set([[11, 2], [3, 4]], [[21, 6]])
  having Eq[Int] = eqIntMod10

// With having:
// Needs Eq[List[List[Int]]]
//   → Check having: not found, search [List[List[Int]]].== needs Eq[List[Int]]
//     → Check having: not found, search [List[Int]].== needs Eq[Int]
//       → Check having: found eqIntMod10 ✓ (LOCAL SCOPE AFFECTS DEEP NESTING!)

// Result: [11, 2] == [21, 6] returns false, but 11 == 21 with mod 10 returns true
```
