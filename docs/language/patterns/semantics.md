# Pattern Matching Semantics

This document describes the semantic rules that govern pattern matching in Jo.

## Pattern Matching Process

Pattern matching proceeds as follows:

1. **Evaluation:** The scrutinee expression is evaluated once
2. **Sequential Testing:** Each case pattern is tested in order
3. **First Match:** The first pattern that succeeds determines which case executes
4. **Binding Scope:** Variables definitely bound in a pattern are available as term names for the corresponding case body
5. **Exhaustiveness:** The compiler warns if patterns don't cover all possible values

```jo
match value
case pattern1 => body1  // Test first
case pattern2 => body2  // Test if pattern1 fails
case pattern3 => body3  // Test if pattern2 fails
end
```

## Flow Typing

Each case pattern forms a single flow typing scope.

!!!note "Local Reasoning Design Principle"
    Pattern matching uses a single flat flow typing scope per case. Nested patterns do not introduce nested scopes—all variables bound within a case pattern share the same scope and flow from left to right. This enables local reasoning by eliminating scope nesting complexity.

    Pattern parameters in pattern definitions are an exception: they are predefined in the flow scope for each case, allowing them to be referenced throughout the pattern body.

### Scoping Rules

- The flow typing goes from left to right, inner to outer
- A pattern variable must be bound at most once in a pattern
- A definitely bound pattern variable can be used both as term name and pattern name in later patterns
- It is an error to use a pattern variable if it is not definitely bound

### Definite Binding Rules

A pattern variable is definitely bound at a point in flow typing if it is definitely assigned when previous patterns are successful. The following rules define when a pattern variable is definitely bound:

#### Variable Pattern `x`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound. It may happen if `x` is a pattern parameter and bound more than once.

```jo
match value
case x => x + 1  // x is definitely bound
end
```

#### Type Pattern `x: T`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound.

```jo
match value
case x: Int => x + 1  // x is definitely bound as Int
end
```

#### Bind Pattern `x @ p`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound. Other variables are definitely bound according to pattern `p`.

```jo
match list
case xs @ Cons(head, tail) =>
  // xs, head, and tail are all definitely bound
  process(xs, head, tail)
end
```

#### Apply Pattern `C(p₁, ..., pₙ)`

A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

```jo
match pair
case Pair(x, y) =>
  // x and y are definitely bound
  x + y
end

// ❌ Error - x bound twice
match pair
case Pair(x, x) => ...
end
```

#### Sequence Pattern `[p₁, ..., pₙ]`

A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

```jo
match list
case [first, second, ..rest] =>
  // first, second, and rest are definitely bound
  process(first, second, rest)
end
```

#### Or-Pattern `p₁ | p₂`

All branches must bind exactly the same set of variables. A variable is definitely bound after the or-pattern if it is bound in all branches.

```jo
// ✓ OK - both bind x
match either
case Left(x) | Right(x) => x
end

// ❌ Error - inconsistent bindings
match result
case Left(x) | Right(y) => ...
end
```

#### And-Pattern `p₁ & p₂`

A variable is definitely bound if it is definitely bound in `p₁` or `p₂`. It is an error if a pattern variable is definitely bound in both `p₁` and `p₂`.

```jo
// ✓ OK - x from first, y from second
case (x, _) & (_, y) => ...

// ❌ Error - x bound in both
case Some(x) & Just(x) => ...
end
```

#### Not-Pattern `!p`

No variables are bound. Variables bound in the nested pattern `p` are not accessible because the not-pattern succeeds only when `p` fails.

```jo
// ✓ OK - no variables bound
match value
case !Positive => "not positive"
end

// ❌ Error - x is not available in the branch
match option
case !(Some(x)) => x  // x not bound when Some(_) doesn't match
end
```

**Rationale:** Since a not-pattern succeeds when its nested pattern fails, any variables that would be bound by the nested pattern have no meaningful values to bind.

#### Guard Pattern `if e`

No variables are bound.

```jo
match value
case x if x > 0 =>
  // x is bound by variable pattern, not guard
  x
end
```

#### Assignment Pattern `then x = e1, y = e2, ...`

The assignment identifiers `x`, `y`, etc. are all definitely bound. These identifiers must reference pattern parameters defined in the pattern definition (not new bindings).

```jo
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0  // n is definitely bound
  case Cons(_, tail) then n = 1 + tail.size  // n is definitely bound
end
```

#### Literal Pattern

No variables are bound.

```jo
match value
case 0 => "zero"
case 1 => "one"
end
```

## Error Examples

### Variable Bound Multiple Times

```jo
// ❌ Error: x is bound twice
match pair
case (x, x) => ...
end

// ❌ Error: x bound in both apply patterns
match pair
case Pair(Some(x), Just(x)) => ...
end
```

### Variable Bound Once in Each Branch (OK)

```jo
// ✓ OK: x bound once in each branch
match either
case Left(x) | Right(x) => x
end
```

### Inconsistent Bindings in OR-Pattern

```jo
// ❌ Error: Left binds x, Right binds y - different variables
match result
case Left(x) | Right(y) => ...
end

// ❌ Error: Left binds x and y, Right binds only x - missing y
match result
case Left(x, y) | Right(x) => ...
end
```

## Valid Flow Typing Examples

### Using Bound Variables in Guards

```jo
match configOpt
case Some(config) if config.database.host is Some(host) =>
  // config bound by Some(config)
  // host bound by is expression
  connect(host)
end
```

### Nested Matching with Flow

```jo
match user
case User(name, age) if age >= 18 =>
  // name and age definitely bound
  // can use both in guard and body
  logger.log("Adult: " + name + ", age: " + age)
end
```

## Type Constraints

- Pattern matching refines types based on successful matches
- Type patterns perform runtime type tests for type parameters
- The type system ensures pattern matching is type-safe

```jo
val value: Int | String = getValue()

match value
case n: Int =>
  // n has type Int (refined from Int | String)
  val doubled = n * 2
case s: String =>
  // s has type String (refined from Int | String)
  val len = s.length
end
```

## Exhaustiveness Checking

The compiler checks that all possible values are covered:

```jo
union Status = Success | Warning | Error

// ✓ OK - all cases covered
match status: Status
case Success => ...
case Warning => ...
case Error => ...
end

// ⚠ Warning - non-exhaustive, missing Error
match status: Status
case Success => ...
case Warning => ...
end
```

### Wildcard for Non-Exhaustive Matches

Use `_` for a catch-all case:

```jo
match status
case Success => ...
case _ => ...  // Catches Warning and Error
end
```

## Pattern Match Failures

Some patterns can fail at runtime:

### Case Definitions

```jo
// Can fail at runtime if pattern doesn't match
case Point(x, y) = getValue()

// Safe - Option ensures exhaustiveness
match getValue()
case Point(x, y) => ...
case _ => ...  // Handle non-Point values
end
```

### For Loops

```jo
// ⚠ Warning - non-exhaustive pattern
for Some(x) in optionList do
  println(x)
end

// ✓ Better - use guard
for elem in optionList if elem is Some(x) do
  println(x)
end
```

## Evaluation Order

Pattern matching follows left-to-right evaluation:

1. Evaluate scrutinee once
2. Test patterns sequentially
3. Evaluate guards in order
4. Execute first matching case body

```jo
match compute()  // Evaluated once
case pattern1 if guard1 => body1  // Test pattern1, then guard1
case pattern2 if guard2 => body2  // Test if pattern1 failed
case _ => body3  // Default case
end
```

## See Also

- [Pattern Forms](pattern-forms.md) - Basic pattern types and composition
- [Pattern Definitions](pattern-definitions.md) - Reusable patterns
