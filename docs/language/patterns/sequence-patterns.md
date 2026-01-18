# Sequence Patterns

Sequence patterns match sequences by matching individual elements and subsequences against patterns.

## Syntax

```
sequence_pattern = "[" [sequence_items] "]"

sequence_items = sequence_item {"," sequence_item}

sequence_item = atom_pattern
              | repeat_pattern

atom_pattern = pattern

repeat_pattern = ".." [ident] ["while" pattern]
```

## Overview

Sequence patterns consist of **sequence item patterns** that match elements or subsequences:

1. **Atom patterns** - Match a single element
2. **Repeat patterns** - Match zero or more elements, optional with a condition

## Scrutinee Type Requirements

For a type `C` to be used as a scrutinee in sequence patterns, it must conform to the following signature:

```jo
def size: Int
def get(i: Int): T
def slice(from: Int, len: Int): C
```

Where:

- `size` returns the number of elements in the sequence
- `get(i)` returns the element at index `i` of type `T`
- `slice(from, len)` returns a subsequence starting at index `from` with length `len`

Types that implement these operations (such as `List`) can be matched against sequence patterns.

## Atom Patterns

**Syntax:** Any pattern that matches a single element

Atom patterns match exactly one element in the sequence at the corresponding position.

```jo
match list
case [] => "empty"
case [x] => "singleton: " + x
case [1, 2, 3] => "exactly [1, 2, 3]"
case [Some(x), None, y] => "three elements with nested patterns"
end

match users
case [User(name, age), _] =>
  "First user: " + name
end
```

## Repeat Patterns

**Syntax:** `..` or `..xs`

Repeat patterns match zero or more consecutive elements in a sequence.

```jo
match list
case [..] => "matches any list"
case [..xs] => "binds entire list to xs"
case [x, ..] => "at least one element"
case [x, ..rest] => "head and tail"
case [.., last] => "at least one element, bind last"
case [first, .., last] => "at least two elements"
end
```

## Guarded Repeat Patterns

**Syntax:** `.. while pattern` or `..xs while pattern`

Guarded repeat patterns match zero or more consecutive elements **while** each element matches the guard pattern.

```jo
match numbers
case [..zeros while 0, ..rest] =>
  // matches leading zeros
  "Stripped leading zeros"

case [..positives while (x if x > 0), ..rest] =>
  // matches while elements are positive
  "Leading positive numbers"
end

match tokens
case [..spaces while ' ', first, ..rest] =>
  // skip leading spaces
  processToken(first)
end
```

### Guard Semantics

The guard pattern is tested against each element in sequence. **The guard itself defines when to stop** - matching continues while the guard holds and stops when:

- An element doesn't match the guard pattern, or
- The end of the sequence is reached

The guard provides explicit control over the repeat boundary. If the guarded repeat consumes elements needed by following patterns, the overall pattern fails.

### Guarded vs Unguarded Behavior

The key difference between guarded and unguarded repeats:

```jo
// Unguarded: cooperative with following patterns
match [1, 2, 3, 4]
case [..xs, 4] =>
  // ✓ Matches: xs = [1, 2, 3], leaves 4 for the atom pattern
end

// Guarded: strict, follows the guard
match [1, 2, 3, 4]
case [.. while x > 0, 4] =>
  // ✗ Fails: guard matches all elements [1,2,3,4], nothing left for 4
end

match [1, 2, 3, 0]
case [.. while x > 0, rest] =>
  // ✓ Matches: guard matches [1,2,3], stops at 0, rest = 0
  // Note: rest is an atom pattern matching single element 0
end

match [1, 2, 3, 0]
case [.. while x > 0, 0] =>
  // ✓ Matches: guard matches [1,2,3], then 0 matches
end
```

## Determinism Rules

A sequence pattern must be **deterministic** - there must be exactly one way to match it against any sequence.

### Repeat Pattern Determinism

A repeat pattern (guarded or unguarded) is deterministic if one of the following holds:

1. **It is the last pattern** in the sequence
   ```jo
   [x, y, ..]              // ✓ Deterministic
   [x, ..xs]               // ✓ Deterministic
   ```

2. **It is followed only by atom patterns** (no other repeats)
   ```jo
   [.., x, y]              // ✓ Deterministic
   [..xs, 1, 2, 3]         // ✓ Deterministic
   [..xs, Some(y), z]      // ✓ Deterministic
   ```

3. **It is explicitly guarded**
   ```jo
   [.. while p, ..rest]            // ✓ Deterministic (both rules apply)
   [..xs while x > 0, ..ys]        // ✓ Deterministic
   [.. while even, .. while odd]   // ✓ Deterministic
   ```

### Invalid Patterns (Non-deterministic)

Patterns that violate determinism are rejected:

```jo
// ❌ Error: First repeat not deterministic
// (not last, not only atoms follow, not guarded)
case [.., ..] => ...
case [..xs, ..ys] => ...
case [..xs, y, ..zs] => ...

// ❌ Error: First repeat not deterministic
case [.., .. while p] => ...

// ✓ OK: First repeat is guarded
case [.. while p, ..] => ...
case [.. while p, ..xs] => ...
case [.. while p, y, ..zs] => ...
```

## Matching Semantics

When a pattern is deterministic, matching proceeds left-to-right:

1. **Unguarded repeat followed by atoms**: Minimally match to allow following atoms to succeed
   ```jo
   [..xs, 1, 2]  // xs matches minimally so [1, 2] can match at end
   ```
   The repeat is "cooperative" - it tries to leave elements for following patterns.

2. **Guarded repeat**: Match strictly according to the guard
   ```jo
   [.. while x > 0, ..rest]  // Matches while x > 0, stops at first x <= 0
   ```
   The guard defines the boundary explicitly. If the guard consumes all elements, following patterns will fail to match.

3. **Final repeat** (unguarded): Greedily take all remaining elements
   ```jo
   [x, y, ..rest]  // rest gets everything after x and y
   ```

## Variable Binding

### Repeat Pattern Variables

Unguarded repeat patterns can bind the matched subsequence to a variable:

```jo
match list
case [..] =>
  // No binding, just matches
  "any list"

case [..xs] =>
  // xs: List[T] - bound to entire list
  "length: " + xs.length

case [first, ..middle, last] =>
  // first: T
  // middle: List[T]
  // last: T
  process(first, middle, last)
end
```

### Guarded Repeat Pattern Variables

Guarded repeat patterns can also bind the matched subsequence:

```jo
match numbers
case [..positives while x > 0, ..rest] =>
  // positives: List[Int] - all leading positive numbers
  // rest: List[Int] - remaining numbers
  "Found " + positives.length + " positive numbers"
end
```

**Important:** Variables bound **within the guard pattern** are not visible outside:

```jo
match list
case [..matched while Some(x), ..rest] =>
  // matched: List[Option[T]] - bound to matched subsequence
  // x: NOT available here - only used during matching
  // rest: List[Option[T]] - remaining elements
  matched.length
end

match pairs
case [..pairs while (a, b), singleton] =>
  // pairs: List[(T1, T2)] - matched pairs
  // a, b: NOT available - only used during guard matching
  // singleton: (T1, T2) - the next element
  pairs.length
end
```

## See Also

- [Pattern Forms](pattern-forms.md) - Overview of all pattern forms
- [Semantics](semantics.md) - Pattern matching semantics
- [Pattern Definitions](pattern-definitions.md) - Reusable patterns
