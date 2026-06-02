# Auto Parameters

Auto parameters enable type-directed argument resolution at call sites. When a call omits an auto argument, the compiler searches the declared candidate list and local scope for a value of the required type.

```jo
// Without auto parameters
def Set[T](l: ..T, eq: Eq[T]): Set[T] = ...
val intSet = Set(1, 2, 3, eqInt)

// With auto parameters
def Set[T](l: ..T)(auto eq: Eq[T] with [eqInt, eqString, [T].==]): Set[T] = ...
val intSet = Set(1, 2, 3)   // eq resolved automatically
```

## Syntax

```
auto_param   = "auto" ident ":" type "with" "[" candidates "]"
candidates   = candidate {"," candidate}
candidate    = qualid | "[" type "]" "." ident
auto_def     = "auto" ident ":" type "=" expr
```

Auto parameters form a separate parameter group following regular parameters. Multiple auto parameters share one group, separated by commas:

```jo
def sort[T](xs: List[T])(auto eq: Eq[T] with [[T].==], ord: Ord[T] with [[T].compare]): List[T] = ...
```

## Candidate Kinds

### Value Candidates

A value candidate is a named value or nullary function. It must be **monomorphic** (no type parameters). It may have auto or context parameters of its own.

```jo
def eqInt: Eq[Int] = (a, b) => a == b       // valid
def eqIntList: Eq[List[Int]] = ...           // valid: monomorphic
def eqList[T](...): Eq[List[T]] = ...        // invalid: has type parameter T
```

### Member Candidates

A member candidate `[T].m` eta-expands method `m` of type `T` into a function where the receiver becomes the first argument:

```
(receiver: T, p1: P1, ..., pn: Pn) => receiver.m(p1, ..., pn)
```

The eta-expanded function must conform to the auto parameter type. Auto and context parameters on `m` carry through to the expansion. When `T` is a type variable, it is instantiated at the call site before the member is looked up.

```jo
// [Int].== eta-expands to (a: Int, b: Int) => a == b : Eq[Int]
def areEqual[T](x: T, y: T)(auto eq: Eq[T] with [[T].==]): Bool = eq(x, y)

// [List[Int]].== eta-expands with a nested auto for element equality:
// (xs: List[Int], ys: List[Int])(auto eqItem: Eq[Int] with [[Int].==]) => xs == ys
```

## Resolution Algorithm

For each omitted auto argument of type `T` with candidate list `[c1, ..., cn]`:

1. **Local scope:** Search for an `auto` definition or `auto` parameter of type `T` in the enclosing scope. Innermost definition wins. If found, use it and stop.
2. **Candidates:** Try `c1, c2, ...` in order. The first that conforms to `T` (or whose eta-expansion conforms to `T`) is used. When a matching candidate itself has auto parameters, they are resolved recursively by the same algorithm.
3. **Identity synthesis:** If `T` is a function type `U => V` with `U <: V`, synthesize the identity function `(x: U) => x`.
4. **Failure:** Report an error with the full search tree.

```jo
def process[T](x: T)(auto eq: Eq[T] with [eqInt, eqString, [T].==]): Unit = ...

process(42)      // eqInt ✓
process("hi")    // eqInt ✗ · eqString ✓
process(user)    // eqInt ✗ · eqString ✗ · [User].== ✓ (eta-expanded)
```

## Local Auto Definitions

An `auto` definition may appear in any local scope (function body or block). It is not allowed at top level or as a class or object member.

```jo
def bar(): Unit =
  auto customEq: Eq[Int] = (a, b) => a % 10 == b % 10
  process(1, 11)   // customEq found in local scope, takes priority over candidates
```

Local auto definitions follow standard block scoping: inner definitions shadow outer ones. Resolution is type-directed — when multiple local autos of the same type are in scope, the innermost wins.

A local auto in scope takes priority over all candidates for every call in that scope, including nested auto requirements resolved during candidate expansion:

```jo
auto customIntEq: Eq[Int] = (a, b) => a % 10 == b % 10

process([1, 11])
// [List[Int]].== needs Eq[Int] → local scope finds customIntEq ✓
```

## Error Reporting

When resolution fails, the compiler displays a search tree showing the full resolution attempt:

- `?` — searching for an auto of this type
- `→` — trying a candidate
- `✓` — candidate succeeded (shown when an enclosing requirement still fails)
- `✗` — candidate failed (type mismatch, cycle, or no candidates)

**Type mismatch:**
```
Failed to find auto of the type Eq[String]
? Eq[String]
  → eqInt ✗ type mismatch: found Eq[Int], expected Eq[String]
```

**Cycle:**
```
Failed to find auto of the type Eq[Int]
? Eq[Int]
  → eqA
      ? Eq[Int]
        → eqB
            ? Eq[Int]
              → eqA ✗ cycle
```

**No candidates:**
```
Failed to find auto of the type Eq[Item]
? Eq[Item]
  ✗ (no candidates)
```

**Nested failure:**
```
Failed to find auto of the type Ord[Item]
? Eq[Item]
  → eqItem ✓
      ? Ord[Item]
        ✗ (no candidates)
```

## Restrictions

- Value candidates must be **monomorphic**: no type parameters. Use member candidates for polymorphic behavior.
- `auto` definitions are only allowed in **local scope**: not at top level, not as class fields or object members.
