# Auto Parameters

## Overview

Auto parameters provide a controlled form of automatic argument resolution at function call sites. When an auto parameter is not explicitly provided, the compiler searches through declared candidate lists and local auto definitions to find a value that matches the required type.

**Three forms of candidates:**

- **Value candidates** (`eqInt`, `eqString`): Named values declared in candidate lists
- **Member candidates** (`[T].==`): The member on a type `T` might satisfy the auto type
- **Local auto definitions**: Auto values or parameters defined in local scope

## Motivation

Auto parameters eliminate repetitive argument passing for configuration values and type class instances while maintaining explicit control over the search space:

```jo
// Without auto parameters
def Set[T](l: ..T, eq: Eq[T]): Set[T] = ...
val intSet = Set(1, 2, 3, eqInt)
val strSet = Set("a", "b", eqString)

// With auto parameters and local overrides
def Set[T](l: ..T)(auto eq: Eq[T] with [eqInt, eqString]): Set[T] = ...
val intSet = Set(1, 2, 3)
val strSet = Set("a", "b")

// Local override - composable across multiple calls
auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10
val set1 = Set(1, 11, 21)  // All use customEq
val set2 = Set(2, 12, 22)  // Also uses customEq
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

**c) Explicit local overrides**

Local auto definitions make non-standard semantics **visible in scope**:

```jo
// Standard - no surprises
process(data)

// Non-standard - EXPLICIT and VISIBLE via local definition
auto customEq: Eq[Int] = ...
auto debugShow: Show[Result] = ...
process(data)  // Uses local autos
```

Anyone reading the code can immediately see:

- That non-standard semantics are defined locally
- Exactly which instances are being used
- The scope in which they apply

This is **superior to Haskell's** "orphan instance somewhere in an imported module invisibly affects everything" model, while being more composable than call-site annotations.

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
auto caseInsensitiveOrd: Ord[String] = ...
sort(strings)

// Natural ordering for filenames - LOCAL override
auto naturalOrd: Ord[String] = ...
sort(filenames)
```

**Benefits:**

- ✅ **Local reasoning:** The behavior is determined by local definitions, not by global state
- ✅ No newtype ceremony
- ✅ Clean types: just `List[String]`
- ✅ Context-appropriate: choose the right semantics for each use
- ✅ Flexible: easy to adapt to local needs
- ✅ Composable: local autos work across all operations in scope

**c) Local search scope via auto definitions**

Local auto definitions provide a **local search scope** that takes priority over all candidate lists, including for nested auto requirements:

```jo
def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

auto customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10
process([1, 2])
// Resolution: [List[Int]].== needs Eq[Int] → checks local scope first → finds customIntEq ✓
// Local auto definitions provide a LOCAL SEARCH SCOPE for nested resolution!
```

This maintains local reasoning: all auto values come from either:

1. Local auto definitions (explicitly in local scope)
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

### Auto Parameter Definition

```
auto_param = "auto" ident ":" type "with" "[" candidate_list "]"
candidate_list = candidate {"," candidate}
candidate = qualid | "[" type "]" "." ident
```

**Value candidate:** Named value (qualified identifier)
**Member candidate:** Type in brackets + dot + member name (`[Int].default`, `[T].eq`)

### Local Auto Definition

```
auto_def = "auto" ident ":" type "=" expr
```

Local auto definitions can appear wherever local value definitions are allowed (in blocks, function bodies, etc.), but **not at top-level or as class fields**.

### Examples

```jo
// Auto parameters with value candidates
def compare(a: T, b: T)(auto eq: Eq[T] with [eqInt, eqString]): Bool = ...

// Auto parameters with member candidates
def areEqual[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool = ...

// Mixed candidates
def process[T](x: T, y: T)(auto eq: Eq[T] with [eqInt, [T].==]): Bool = ...

// Multiple auto parameters
def sort[T](xs: List[T])(auto eq: Eq[T] with [[T].==], ord: Ord[T] with [[T].compare]): List[T] = ...

// Local auto definition to override resolution
auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10
process(1, 11)  // Uses customEq

// Local auto parameters (in function definitions)
def foo(auto eq: Eq[Int] with [eqInt]): Unit =
  process(1, 2)  // Can use the auto parameter eq
```

## Semantics

### Search Algorithm

For call `f(arg)` where there's an auto parameter `auto x: T with [c1, c2, ..., cn]`:

1. **Check local scope first:** Search for local auto definitions of type `T` in the enclosing scope
    - Local auto definitions: `auto name: T = expr`
    - Local auto parameters: function parameters declared with `auto`
    - Lookup follows standard scoping rules (inner scopes shadow outer scopes)
    - If multiple local autos have the same type, the innermost one is used
2. **Search candidates in order (if not found in local scope):**
    - **Value candidate** `ci` qualifies if `ci` conforms to `T`
    - **Member candidate** `[U].member` qualifies if `(a: U, x_i: X_i) => a.member(x_i)` has the type `T`, where `x: X` is the parameter type(s) of `member`.
    - **When a candidate has auto parameters itself**, resolve those nested autos recursively using the same algorithm (checking local scope first, then the nested candidate list)
3. **First match wins:** Stop after first successful candidate
4. **No match:** Report error that no suitable auto value was found

**Critical: Local scope provides search priority**

Local auto definitions establish a **local search scope** that takes priority over all candidate lists. When resolving nested auto requirements:

1. First check local scope for auto definitions or parameters of the matching type
2. Only if not found in local scope, search the candidate list

This ensures **no global or magic search scope** - all auto resolution is either from explicit local definitions or from declared candidate lists.

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

**Member candidates with auto parameters:** When a member candidate itself has auto parameters (like `List[T].==` with `auto eqItem: Eq[T]`), those nested autos are resolved using the same algorithm:

1. Check local scope for auto definitions of the matching type
2. If not found, search the nested auto parameter's candidate list

This enables compositional type class instances: `List[Int].==` automatically finds `Int.==` for element comparison, and callers can override with local auto definitions like `auto customEq: Eq[Int] = ...`.

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

### Local Auto Definitions

Callers can explicitly provide auto values using local auto definitions:

```jo
def process[T](x: T)(auto eq: Eq[T] with [eqInt, eqString]): Unit = ...

process(42)                        // Uses eqInt (from candidates)

auto customEq: Eq[Int] = ...
process(42)                        // Uses customEq (from local scope)
```

**Scoping rules:**

- Local auto definitions follow standard block scoping
- Inner scopes shadow outer scopes
- Multiple local autos of the same type can exist in different scopes; innermost wins
- Local autos are resolved by type, not by name

**Examples:**
```jo
// Multiple auto parameters
def compare[T](a: T, b: T)(auto eq: Eq[T] with [eqInt], ord: Ord[T] with [ordInt]): Int = ...

compare(1, 2)                      // Both from candidates

auto customEq: Eq[Int] = ...
compare(1, 2)                      // Override eq only

auto customOrd: Ord[Int] = ...
compare(1, 2)                      // Override both (both in scope)
```

**Nested auto resolution with local search scope:**

The key advantage of local auto definitions is providing a local search scope for nested auto requirements. Local autos are tried **first** before searching candidate lists:

```jo
def eqInt: Eq[Int] = (a, b) => a == b

def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

// Example 1: No local auto
process([1, 2])
// Resolution:
// 1. Need Eq[List[Int]], check local scope: not found, search candidates: finds [List[Int]].==
// 2. [List[Int]].== needs Eq[Int], check local scope: not found, search its candidates [[Int].==]: finds [Int].== ✓

// Example 2: Provide nested requirement via local auto
auto customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10
process([1, 2])
// Resolution:
// 1. Need Eq[List[Int]], check local scope: not found, search candidates: finds [List[Int]].==
// 2. [List[Int]].== needs Eq[Int], check local scope: finds customIntEq ✓
// The local auto provides customIntEq for the nested requirement!

// Example 3: Override both outer and nested
auto customListEq: Eq[List[Int]] = (a, b) => a.size == b.size
process([1, 2])
// Resolution:
// 1. Need Eq[List[Int]], check local scope: finds customListEq ✓
// (customIntEq is still in scope but not needed since customListEq is used directly)
```

**Key insight:** Local auto definitions create a search scope that shadows candidate lists for **all nested auto resolution** within their scope. This maintains explicitness - no global search, just locally-scoped overrides.

**Composability:** Unlike call-site annotations, local auto definitions work across all calls in their scope:

```jo
auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10

val set = Set(1, 11, 21)          // Uses customEq
val result = process(1, 11)       // Uses customEq
val map = Map((1, "a"), (11, "b")) // Uses customEq
```

**Same type in multiple parameters:**

When multiple auto parameters have the same type, one local auto satisfies all of them:

```jo
def compare[T](a: T, b: T)(auto eq1: Eq[T] with [eqInt], eq2: Eq[T] with [eqInt]): Bool = ...

auto customEq: Eq[Int] = ...
compare(1, 2)  // Both eq1 and eq2 receive customEq
```

**Design note:** This is a deliberate choice—auto resolution is type-directed. If you need different instances for different purposes, use distinct types (e.g., `StrictEq[T]` vs `FuzzyEq[T]`).

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

### Example: Local Auto Definitions

When local auto definitions are available in scope, they appear in the search tree:

```jo
def test[T](x: T, y: T)(auto eq: Eq[T] with []): Bool = eq(x, y)

auto customEq: Eq[Int] = ...
val result = test(42, 43)
```

If resolution succeeds using a local auto, it appears as:
```
? Eq[Int]
  → (local: customEq: Eq[Int]) ✓
```

If a local auto is found but nested resolution fails, the tree shows both:
```
? Eq[Int]
  → (local: customEq: Eq[Int])
      ? Ord[Int]
        ✗ (no candidates)
```

## Restrictions

### Auto Definition Scope

Auto definitions are **only allowed in local scope** (function bodies, blocks, etc.). They are **not allowed**:

- At top-level (module scope)
- As class fields
- As object members

```jo
// Invalid - top-level
auto globalEq: Eq[Int] = ...  // Error

// Invalid - class field
class Foo
  auto eq: Eq[Int] = ...      // Error
end

// Valid - local definition
def bar: Unit =
  auto eq: Eq[Int] = ...      // OK
  process(1, 2)
```

**Rationale:** Restricting auto definitions to local scope prevents implicit global state and maintains predictable scoping. Candidate lists in function signatures provide the mechanism for explicit top-level reusable auto values.

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

process(1, 1)                    // Uses eqInt → true
process(1, -1)                   // Uses eqInt → false

auto eqIntAbsEqual: Eq[Int] = (a, b) => abs(a) == abs(b)
process(1, -1)                   // Uses local override → true
```

### Context Parameters Integration

```jo
param precision: Int = 2

def eqFloat: Eq[Float] receives precision = (a, b) =>
  val threshold = pow(10.0, -precision)
  abs(a - b) < threshold

def compare(x: Float, y: Float)(auto eq: Eq[Float] with [eqFloat]): Bool =
  eq(x, y)

compare(1.001, 1.002)                    // Uses eqFloat with precision=2 → true
compare(1.001, 1.002) with precision = 4 // Uses eqFloat with precision=4 → false

auto exactEqFloat: Eq[Float] = (a, b) => a == b
compare(1.001, 1.009)                    // Uses exact equality from local auto
```

## Additional Design Rationale

### Why Explicit Candidate Lists?

Unlike Scala's implicit scope rules, Jo requires explicit candidate lists because:

- **Readability:** Function signatures show exactly where auto values come from
- **Predictability:** No hidden scope rules or import-dependent behavior
- **Simplicity:** No need to understand complex implicit resolution rules
- **Local reasoning:** Can understand function behavior from its signature alone

### Why `with` for Candidate Lists?

The keyword `with` introduces the candidate list for auto parameters:

```jo
auto eq: Eq[T] with [eqInt, eqString]
```

This clearly indicates "search with these candidates" and aligns with the `with` keyword used elsewhere in the language (e.g., parameter adapters use `with` to specify transformation chains).

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

### Why Local Auto Definitions?

Local auto definitions provide **type-based resolution** in local scope for several reasons:

**1. Composability:**

Unlike call-site annotations, local auto definitions work across all operations in their scope:

```jo
auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10

val set = Set(1, 11, 21)          // Uses customEq
val result = process(1, 11)       // Uses customEq
val map = Map((1, "a"), (11, "b")) // Uses customEq
```

This is the key improvement over the `having` clause, which only worked with immediate call expressions.

**2. Local search scope for nested auto resolution:**

Local autos provide a **local search scope** that takes priority over all candidate lists, including for nested auto requirements:

```jo
def eqInt: Eq[Int] = (a, b) => a == b

class List[T]
  def ==(other: List[T])(auto eq: Eq[T] with [eqInt]): Bool = ...
end

def process[T](xs: List[T])(auto eq: Eq[List[T]] with [[List[T]].==]): Unit = ...

auto customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10
process([1, 2])
// Resolution: [List[Int]].== needs Eq[Int] → checks local scope first → finds customIntEq ✓
```

**3. Type-directed nature:**

Auto parameters are fundamentally about type-directed resolution. Local autos align with this principle:

- Auto resolution searches by type (first in local scope, then in candidates)
- Local autos are resolved by type, not by name
- Maintains consistency: types everywhere, no name-based lookup

**4. Standard scoping:**

Local auto definitions follow standard block scoping rules:

```jo
def foo: Unit =
  auto eq1: Eq[Int] = ...
  process(1, 2)  // Uses eq1

  if condition then
    auto eq2: Eq[Int] = ...  // Shadows eq1 in this scope
    process(3, 4)  // Uses eq2
```

**5. No global search scope:**

The design maintains explicitness: auto values come from either:

1. Local auto definitions (explicit in local scope)
2. Declared candidate lists (explicit in signature)

There is **no global or implicit search scope** - everything is either locally defined or explicitly declared in candidate lists. Top-level autos are disallowed to prevent implicit global state.

### Type Safety

Auto parameters are fully type-safe:

1. **Candidates are type-checked at definition site:** Each candidate in `with [...]` must type-check and conform to the auto parameter's type.

2. **Resolution preserves types:** The search algorithm only selects candidates whose type matches the required type (via conformance/subtyping).

3. **Local autos are type-checked:** Auto definitions `auto x: T = expr` are type-checked to ensure `expr` conforms to `T`.

4. **Eta-expansion is type-preserving:** Member candidates `[T].member` are eta-expanded using standard type rules, preserving soundness.

5. **Termination is guaranteed:** The restriction that candidates cannot reference the defining function prevents infinite recursion during resolution.

**Therefore:** Auto resolution introduces no runtime type errors. All type checking happens at compile time.

### Implementation Complexity

The auto resolution algorithm is straightforward to implement:

**Time complexity:** O(candidates × nesting depth) per auto parameter. Since candidate lists are typically small (2-5 items) and nesting depth is usually shallow (1-3 levels), this is efficient in practice.

**Space complexity:** The local scope environment needs to be consulted during auto resolution, which is a standard scope lookup operation (already part of normal compilation).

**Separate compilation:** Auto parameters are signature-visible, so they appear in compiled module interfaces. Member candidate resolution requires member lookup on types, which is already part of normal compilation.

**Error recovery:** When auto resolution fails, the compiler has enough context to provide helpful error messages showing which candidates were tried and why they failed.
