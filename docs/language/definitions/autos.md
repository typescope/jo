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
def Set[T](l: ..T)(auto eq: Eq[T] with [eqInt, eqString]): Set[T] = ...
val intSet = Set(1, 2, 3)
val strSet = Set("a", "b")
```

## Design Philosophy

Jo's auto parameters are built on two foundational principles that distinguish them from Haskell type classes and Scala implicits:

### 1. Explicitness

**Principle:** Code behavior should be visible and transparent. No hidden mechanisms, no compiler magic, no implicit derivations.

**How this principle manifests in auto parameters:**

**a) Explicit candidate lists in signatures**

Unlike Scala's implicit scope or Haskell's typeclass instances, Jo requires explicit candidate lists:

```jo
// The signature tells you EXACTLY where auto values come from
def process[T](x: T)(auto eq: Eq[T] with [eqInt, eqString, [T].==]): Unit = ...

// vs Scala: where does the Eq[T] come from? Implicit scope? Imports? Magic?
def process[T](x: T)(implicit eq: Eq[T]): Unit = ...
```

**Benefits:**

- No need to understand complex implicit resolution rules
- Function signatures are self-documenting
- No import-dependent behavior

**b) Explicit instance composition**

**Design decision:** Value candidates cannot have type parameters. Users write instances explicitly.

Consider this Scala/Haskell scenario:

```scala
// What code actually runs here?
summon[Eq[List[Option[Map[String, Either[Int, User]]]]]]
```

The compiler implicitly:

- Derives `Eq[List[A]]` from `Eq[A]`
- Derives `Eq[Option[A]]` from `Eq[A]`
- Derives `Eq[Map[K,V]]` from `Eq[K]` and `Eq[V]`
- Derives `Eq[Either[A,B]]` from `Eq[A]` and `Eq[B]`
- Recursively composes all of these...

**Problems with implicit derivation:**

- **Debugging nightmare:** When equality fails, which derived instance is wrong?
- **Performance opacity:** Is it doing structural comparison? Hashing? Something else?
- **Semantic ambiguity:** Different composition strategies may be valid but give different results
- **Magic behavior:** Hard to understand what code actually executes

**Jo's approach:** Write instances explicitly.

```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqString: Eq[String] = (a, b) => a == b
def eqIntList: Eq[List[Int]] = (xs, ys) =>
  if xs.size != ys.size then false
  else ... // Explicit structural comparison logic
```

**Benefits:**

- **Clarity:** You know exactly what code runs
- **Control:** You can optimize (use hashing, custom algorithms, etc.)
- **Debuggability:** Set breakpoints in your explicit implementation
- **Correctness:** You choose the right semantics (e.g., fuzzy equality for floats)

**For types you own**, member candidates provide clean composition while staying explicit:

```jo
class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] with [[T].==]): Bool =
    // Explicit structural comparison with explicit recursion
    if this.size != that.size then false
    else
      var i = 0
      var equal = true
      while equal && i < this.size do
        equal = eqItem(this[i], that[i])  // Explicit element comparison
        i = i + 1
      equal
end

def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...
```

You explicitly write the structural comparison logic. The recursion to element equality is explicit (`auto eqItem`). **This is much clearer** than invisible compiler-generated derivation.

**c) Explicit call-site overrides**

The `having` clause makes non-standard semantics **visible at the call site**:

```jo
// Standard - no surprises
process(data)

// Non-standard - EXPLICIT and VISIBLE
process(data) having Eq[Int] = customEq, Show[Result] = debugShow
```

Anyone reading the code can immediately see:

- That non-standard semantics are being used
- Exactly which instances are being used
- Where to look if something goes wrong

This is **superior to Haskell's** "orphan instance somewhere in an imported module invisibly affects everything" model.

### 2. Local Reasoning

**Principle:** You should be able to understand code behavior from local context alone, without needing global knowledge or complex rules.

**How this principle manifests in auto parameters:**

**a) Self-contained function signatures**

Everything you need to know about auto resolution is in the function signature:

```jo
def sort[T](xs: List[T])(auto eq: Eq[T] with [[T].==], ord: Ord[T] with [[T].compare]): List[T] = ...

// Reading this signature tells you:
// - It needs Eq[T] and Ord[T]
// - Eq[T] comes from [T].== (the == method on T)
// - Ord[T] comes from [T].compare (the compare method on T)
// - No hidden global scope or import-dependent behavior
```

You can reason about the function without understanding global implicit scopes or complex resolution rules.

**b) Per-call-site coherence (not global coherence)**

**Design decision:** Different call sites can use different instances for the same type.

Global coherence (Haskell-style) forces you to choose **one canonical instance** per type globally. But this breaks local reasoning: you need to know which global instance was chosen, possibly in a different module.

**Example: Ord[String]**

Which is "the" ordering for strings?

- Lexicographic (case-sensitive)
- Case-insensitive
- Natural ordering ("file2.txt" < "file10.txt")
- Locale-specific (Swedish treats 'ä' differently than English)
- By length

**There is no universal answer!** The right ordering depends on context.

**Haskell approach:**
```haskell
-- Forced to pick ONE global instance
instance Ord String where compare = ...  -- lexicographic only

-- Want case-insensitive? Need newtype wrapper!
newtype CaseInsensitive = CI String
instance Ord CaseInsensitive where compare (CI s1) (CI s2) = ...

sort (map CI strings)  -- Ceremony everywhere!
```

**Problems:**

- ❌ Need global knowledge of which instance was chosen
- ❌ Type pollution: `List String` vs `List CaseInsensitive` are incompatible
- ❌ Newtype ceremony: constant wrapping/unwrapping
- ❌ Can't easily adapt behavior to local context

**Jo approach:**
```jo
// Default context
sort(strings)  // Uses [String].compare

// Case-insensitive context - LOCAL override
sort(strings) having Ord[String] = caseInsensitiveOrd

// Natural ordering for filenames - LOCAL override
sort(filenames) having Ord[String] = naturalOrd
```

**Benefits:**

- ✅ **Local reasoning:** The behavior is determined at the call site, not by global state
- ✅ No newtype ceremony
- ✅ Clean types: just `List[String]`
- ✅ Context-appropriate: choose the right semantics for each use
- ✅ Flexible: easy to adapt to local needs

**c) Local search scope via `having`**

The `having` clause provides a **local search scope** that takes priority over all candidate lists, including for nested auto requirements:

```jo
def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

def customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

process([1, 2]) having Eq[Int] = customIntEq
// Resolution: [List[Int]].== needs Eq[Int] → checks having first → finds customIntEq ✓
// The having clause provides a LOCAL SEARCH SCOPE for nested resolution!
```

This maintains local reasoning: all auto values come from either:

1. The `having` clause (explicitly at call site)
2. Declared candidate lists (explicitly in signature)

**No global or implicit search scope.** Everything is locally visible.

### Summary

These two principles work together to create a type class system that is:

- **Transparent:** You can see where values come from and what code runs
- **Understandable:** No need to understand complex global rules
- **Controllable:** Explicit control at both definition site and call site
- **Flexible:** Adapt behavior to local context without global ceremony

The result prioritizes **clarity and control** over **convenience and magic**. This aligns with Jo's broader philosophy: code should be easy to understand and reason about, even if it requires more explicit specification.

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
def compare(a: T, b: T)(auto eq: Eq[T] with [eqInt, eqString]): Bool = ...

// Member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool = ...

// Mixed candidates
def process[T](x: T, y: T)(auto eq: Eq[T] with [eqInt, [T].==]): Bool = ...

// Multiple auto parameters
def sort[T](xs: List[T])(auto eq: Eq[T] with [[T].==], ord: Ord[T] with [[T].compare]): List[T] = ...

// Explicit provision at call site (by type)
process(x, y) having Eq[Int] = customEq
sort(xs) having Eq[String] = customEq, Ord[String] = customOrd
```

## Semantics

### Search Algorithm

For call `f(arg) having T1 = v1, T2 = v2` where there's an auto parameter `auto x: T with [c1, c2, ..., cn]`:

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
- **Cannot have type parameters** - must be monomorphic
- Type must match the auto parameter's type (subtyping allowed)
- Cannot directly or indirectly reference the function being defined (to ensure termination)

**Allowed:**

- Can have auto parameters themselves (nesting allowed)
- Can have context parameters

**Rationale for "no regular parameters":** This avoids ambiguity about partial application. If you need parameterized candidates, wrap them in non-parameterized definitions.

**Rationale for "no type parameters":** This keeps auto resolution simple and predictable, avoiding complex type inference during candidate matching. For polymorphic behavior, use member candidates like `[T].==` instead.

**Examples:**
```jo
def eqInt: Eq[Int] = (a, b) => a == b                    // Valid
def eqIntList: Eq[List[Int]] = ...                       // Valid (monomorphic)
def showInt: String receives formatter = ...             // Valid (context OK)

def eqList[T](auto eqItem: Eq[T] with [eqInt]): Eq[List[T]] = ...  // Invalid (has type parameter)
```

**Resolution with nesting (using member candidates):**
```jo
// Member candidates can be polymorphic through type instantiation
def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

// For List[Int]:
// [List[Int]].== eta-expands to (a: List[Int], b: List[Int])(auto eqItem: Eq[Int] with [[Int].==]) => a == b
// Nested auto eqItem: Eq[Int] searches [Int].== ✓

process([1, 2, 3])   // Uses [List[Int]].==, which uses [Int].== for element comparison
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

def process[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool = eq(x, y)

// For T=Int:
// [Int].== refers to Int.==
// Eta-expansion: (receiver: Int, that: Int) => receiver == that
// Type: (Int, Int) => Bool which is Eq[Int] ✓

process(42, 43)  // Uses eta-expanded Int.==
```

**Member candidate with auto parameter:**
```jo
class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] with [[T].==]): Bool = ...
end

def process[T](xs: List[T], ys: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Bool = eq(xs, ys)

// For T=Int:
// [List[Int]].== refers to List[Int].==
// Eta-expansion: (receiver: List[Int], that: List[Int])(auto eqItem: Eq[Int] with [[Int].==]) => receiver == that
// Type: (List[Int], List[Int])(auto Eq[Int] with [[Int].==]) => Bool which is Eq[List[Int]] ✓
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
def eqString: Eq[String] = ...

def process[T](x: T)(auto eq: Eq[T] with [eqInt, eqString, [T].==]): Unit = ...

process(42)      // Tries eqInt ✓ (stops, doesn't try others)
process("hi")    // Tries eqInt ✗ (Int != String), tries eqString ✓ (stops)
process(user)    // Tries eqInt ✗, tries eqString ✗, tries [User].== ✓
```

### Explicit Provision with `having`

Callers can explicitly provide auto arguments using the `having` keyword **by type**:

```jo
def process[T](x: T)(auto eq: Eq[T] with [eqInt, eqString]): Unit = ...

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
def compare[T](a: T, b: T)(auto eq: Eq[T] with [eqInt], ord: Ord[T] with [ordInt]): Int = ...

compare(1, 2)                                    // Both from candidates
compare(1, 2) having Eq[Int] = customEq          // Override eq only
compare(1, 2) having Eq[Int] = customEq, Ord[Int] = customOrd  // Override both
```

**Nested auto resolution with local search scope:**

The key advantage of type-based `having` is providing a local search scope for nested auto requirements. The `having` clause values are tried **first** before searching candidate lists:

```jo
def eqInt: Eq[Int] = (a, b) => a == b

def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

// Example 1: No having clause
process([1, 2])
// Resolution:
// 1. Need Eq[List[Int]], search candidates: finds [List[Int]].==
// 2. [List[Int]].== needs Eq[Int], search its candidates [[Int].==]: finds [Int].== ✓

// Example 2: Provide nested requirement via having
def customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

process([1, 2]) having Eq[Int] = customIntEq
// Resolution:
// 1. Need Eq[List[Int]], check having: not found, search candidates: finds [List[Int]].==
// 2. [List[Int]].== needs Eq[Int], check having: finds customIntEq ✓
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
def compare[T](a: T, b: T)(auto eq1: Eq[T] with [eqInt], eq2: Eq[T] with [eqInt]): Bool = ...

// Both eq1 and eq2 receive customEq
compare(1, 2) having Eq[Int] = customEq
```

**Design note:** This is a deliberate choice—`having` provides values **by type**, not by parameter name. If you need different instances for different purposes, use distinct types (e.g., `StrictEq[T]` vs `FuzzyEq[T]`). This maintains the type-directed nature of auto resolution and prevents having clauses from becoming overly verbose.

## Error Reporting with Search Trees

When auto resolution fails, the compiler displays a **search tree** showing the entire resolution attempt. This makes debugging auto resolution failures straightforward and transparent.

### Search Tree Format

The search tree uses a concise visual format:

- **`?`** - Searching for an auto parameter of this type
- **`→`** - Trying a candidate
- **`✓`** - Candidate succeeded (but other autos failed)
- **`✗`** - Candidate failed (with reason)

### Example: No Candidates Available

```jo
type Eq[T] = (T, T) => Bool

def test(x: Item, y: Item)(auto eq: Eq[Item]): Bool =
  eq(x, y)

def main: Unit =
  val i1 = new Item(42)
  val i2 = new Item(100)
  val result = test(i1, i2)  // Error: no candidates
```

**Error message:**
```
Failed to find auto of the type Eq[Item]
? Eq[Item]
  ✗ (no candidates)
```

### Example: Cycle Detection

```jo
type Eq[T] = (T, T) => Bool

def eqA(auto eq: Eq[Int] with [eqB]): Eq[Int] = ...
def eqB(auto eq: Eq[Int] with [eqA]): Eq[Int] = ...

def test(x: Int, y: Int)(auto eq: Eq[Int] with [eqA]): Bool =
  eq(x, y)
```

**Error message:**
```
Failed to find auto of the type Eq[Int]
? Eq[Int]
  → eqA
      ? Eq[Int]
        → eqB
            ? Eq[Int]
              → eqA ✗ cycle
```

The tree clearly shows the cycle: `eqA → eqB → eqA`.

### Example: Mixed Value and Member Candidates

```jo
type Eq[T] = (T, T) => Bool

class Item
  val value: Int

class Box
  val item: Item
  def compare(that: Box)(auto eq: Eq[Item] with [eqItem]): Bool = ...

def eqItem(auto eq: Eq[Box] with [[Box].compare]): Eq[Item] = ...

def testEq[T](x: T, y: T)(auto eq: Eq[T] with [eqItem]): Bool =
  eq(x, y)
```

**Error message:**
```
Failed to find auto of the type Eq[Item]
? Eq[Item]
  → eqItem
      ? Eq[Box]
        → [Box].compare
            ? Eq[Item]
              → eqItem ✗ cycle
```

The tree shows:

1. Looking for `Eq[Item]`
2. Trying value candidate `eqItem`
3. `eqItem` needs `Eq[Box]`
4. Trying member candidate `[Box].compare`
5. `[Box].compare` needs `Eq[Item]` again → cycle detected

### Example: Type Mismatch

```jo
type Eq[T] = (T, T) => Bool

def eqInt: Eq[Int] = (a, b) => a == b

def test(s: String)(auto eq: Eq[String] with [eqInt]): Bool = ...
```

**Error message:**
```
Failed to find auto of the type Eq[String]
? Eq[String]
  → eqInt ✗ type mismatch: found Eq[Int], expected Eq[String]
```

### Example: Nested Resolution

```jo
type Eq[T] = (T, T) => Bool

def eqItem(auto ord: Ord[Item]): Eq[Item] = ...

def test(x: Item, y: Item)(auto eq: Eq[Item] with [eqItem]): Bool = ...
```

**Error message:**
```
Failed to find auto of the type Ord[Item]
? Ord[Item]
  ✗ (no candidates)
```

When nested resolution fails, the error points to the specific nested requirement that couldn't be satisfied.

### Example: Having Candidates

When candidates are provided via the `having` clause, they appear in the search tree with their type signature:

```jo
def test[T](x: T, y: T)(auto eq: Eq[T]): Bool = eq(x, y)

def customEq: Eq[Int] = ...

val result = test(42, 43) having Eq[Int] = customEq
```

If resolution fails during nested auto resolution, having candidates appear as:
```
? Eq[Int]
  → (having: (): Eq[Int] receives none) ✓
```

## Restrictions

### Parameter Adapters

Auto parameters cannot have parameter adapters.

```jo
def foo(auto ctx: Context with [ctx1] with [adapter]): Unit = ...  // Invalid
```

**Rationale:** Auto parameters are for automatic resolution, not argument conversion. Mixing both features would be confusing.

### Polymorphism

Auto parameters with type variables and their candidates must follow polymorphism rules:

- **Value candidates cannot have type parameters** - they must be monomorphic
- Member candidates work with type parameters naturally through type instantiation

**Rationale:** Disallowing type parameters on value candidates keeps auto resolution simple and predictable. We avoid complex type inference during candidate matching, which would be required to instantiate polymorphic candidates. Member candidates provide the needed extensibility for generic types through eta-expansion.

```jo
// Invalid - value candidate cannot have type parameters
def eqList[T](auto eq: Eq[T] with [eqInt]): Eq[List[T]] = ...  // Has type parameter T
def foo[T](auto eq: Eq[T] with [eqList]): Unit = ...             // Error: eqList is polymorphic

// Valid - use member candidate instead for polymorphic behavior
def bar[T](auto eq: Eq[T] with [[T].==]): Unit = ...  // Member candidate works with type parameters

// Valid - multiple monomorphic candidates for specific types
def eqIntList: Eq[List[Int]] = ...     // Monomorphic, specific to List[Int]
def eqStringList: Eq[List[String]] = ...  // Monomorphic, specific to List[String]
def baz(xs: List[Int])(auto eq: Eq[List[Int]] with [eqIntList]): Unit = ...  // OK
```

### Position

Auto parameters must appear in their own parameter list, after regular parameters.

```jo
// Valid
def foo(x: Int)(auto eq: Eq[T] with [eqInt]): Unit = ...

// Valid - multiple auto parameters in same list
def foo(x: Int)(auto eq: Eq[T] with [eqInt], ord: Ord[T] with [ordInt]): Unit = ...

// Invalid - mixed with regular parameters
def foo(x: Int, auto eq: Eq[T] with [eqInt]): Unit = ...
```

**Rationale:** Clear separation between explicit arguments and auto arguments improves readability.

## Examples

### Type Class Pattern

```jo
type Eq[T] = (T, T) => Bool

def eqInt: Eq[Int] = (a, b) => a == b
def eqString: Eq[String] = (a, b) => a == b

// For composite types, use member candidates
class List[T]
  def ==(that: List[T])(auto eqItem: Eq[T] with [[T].==]): Bool =
    if this.size != that.size then false
    else
      var equal = true
      var i = 0
      while equal && i < this.size do
        equal = eqItem(this[i], that[i])
        i = i + 1
      equal
end

// Set implementation using auto parameter with member candidate
def Set[T](l: ..T)(auto eq: Eq[T] with [eqInt, eqString, [T].==]): Set[T] = {
  def contains(x: T): Bool = l.exists(e => eq(e, x))
  def +(x: T): Set[T] = if contains(x) then this else Set(..(l + x))
  // ...
}

// Usage - auto parameters resolved automatically
val intSet = Set(1, 2, 3)           // Uses eqInt
val strSet = Set("a", "b")          // Uses eqString
val listSet = Set([1,2], [3,4])     // Uses [List[Int]].==, which uses [Int].== for elements
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
  def ==(that: List[T])(auto eqItem: Eq[T] with [[T].==]): Bool = ...
end

// Generic function using member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool =
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

def compare[T](x: T, y: T)(auto eq: Eq[T] with [eqIntMod10, [T].==]): Bool =
  eq(x, y)

compare(15, 25)         // Uses eqIntMod10 → true (both end in 5)
compare("a", "a")       // eqIntMod10 doesn't match String, uses eta-expanded String.== → true
```

### Explicit Override

```jo
def eqInt: Eq[Int] = (a, b) => a == b
def eqIntAbsEqual: Eq[Int] = (a, b) => abs(a) == abs(b)

def process(x: Int, y: Int)(auto eq: Eq[Int] with [eqInt]): Bool =
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

def compare(x: Float, y: Float)(auto eq: Eq[Float] with [eqFloat]): Bool =
  eq(x, y)

compare(1.001, 1.002)                                // Uses eqFloat with precision=2 → true
compare(1.001, 1.002) with precision = 4             // Uses eqFloat with precision=4 → false
compare(1.001, 1.009) having Eq[Float] = exactEqFloat  // Uses exact equality
```

## Additional Design Rationale

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
def areEqual[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool = eq(x, y)

// Eta-expansion: Int.== → (a: Int, b: Int) => a == b → Eq[Int]
areEqual(1, 2)  // Works automatically
```

### Why Type-Based `having`?

The `having` clause uses **type-based binding** (`Eq[T] = value`) instead of **name-based binding** (`eq = value`) for several reasons:

**1. Local search scope for nested auto resolution:**

The `having` clause provides a **local search scope** that takes priority over all candidate lists, including for nested auto requirements. This is the key feature that enables fine-grained control:

```jo
def eqInt: Eq[Int] = (a, b) => a == b

class List[T]
  def ==(other: List[T])(auto eq: Eq[T] with [eqInt]): Bool = ...
end

def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

def customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

// The having clause provides Eq[Int] for the nested requirement
process([1, 2]) having Eq[Int] = customIntEq
// Resolution: [List[Int]].== needs Eq[Int] → checks having first → finds customIntEq ✓
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
def foo[T](x: List[T])(auto eq1: Eq[T] with [...], eq2: Eq[T] with [...]): Unit = ...
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

1. **Candidates are type-checked at definition site:** Each candidate in `with [...]` must type-check and conform to the auto parameter's type.

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
