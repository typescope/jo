# Flow Typing for Patterns

This document describes the flow typing rules that govern pattern matching in Jo. The flow typing for expressions is defined in [is-expression](../expressions/is-expression.md).

## Flow Scope

Each case pattern forms a single flow typing scope.

::: info Local Reasoning Design Principle
Pattern matching uses a single flat flow typing scope per case. Nested patterns do not introduce nested scopes—all variables bound within a case pattern share the same scope and flow from left to right. This enables local reasoning by eliminating scope nesting complexity.

Pattern parameters in pattern definitions are an exception: they are predefined in the flow scope for each case, allowing them to be referenced throughout the pattern body.
:::

The flow scope follow the following rules:

- The flow typing goes from left to right, inner to outer for complex patterns
- A pattern variable may be bound at most once in a pattern
- A definitely bound pattern variable can be used both as term name and pattern name in later patterns
- It is an error to use a pattern variable if it is not definitely bound

## Definite Binding Rules

A pattern variable is definitely bound at a point in flow typing if it is definitely assigned when previous patterns are successful. The following rules define when a pattern variable is definitely bound:

### Variable Pattern `x`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound. It may happen if `x` is a pattern parameter and bound more than once.

```jo
match value
case x => x + 1  // x is definitely bound
```

### Type Pattern `x: T`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound.

```jo
match value
case x: Int => x + 1  // x is definitely bound as Int
```

### Bind Pattern `x @ p`

The variable `x` is definitely bound. It is an error if `x` is already definitely bound. Other variables are definitely bound according to pattern `p`.

```jo
match list
case xs @ Cons(head, tail) =>
  // xs, head, and tail are all definitely bound
  process(xs, head, tail)
end
```

### Apply Pattern `C(p₁, ..., pₙ)`

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
```

### Sequence Pattern `[p₁, ..., pₙ]`

A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

```jo
match list
case [first, second, ..rest] =>
  // first, second, and rest are definitely bound
  process(first, second, rest)
end
```

### Or-Pattern `p₁ | p₂`

A variable is definitely bound after the or-pattern if it is bound in all branches.

```jo
// ✓ OK - both bind x
match either
case Left(x) | Right(x) => x

// ✓ OK - but neither x nor y can be used in the branch
match result
case Left(x) | Right(y) => ...
```

### And-Pattern `p₁ & p₂`

A variable is definitely bound if it is definitely bound in `p₁` or `p₂`. It is an error if a pattern variable is definitely bound in both `p₁` and `p₂`.

```jo
// ✓ OK - x from first, y from second
case (x, _) & (_, y) => ...

// ❌ Error - x bound in both
case Some(x) & Just(x) => ...
```

### Not-Pattern `!p`

No variables are bound. Variables bound in the nested pattern `p` are not accessible because the not-pattern succeeds only when `p` fails.

```jo
// ✓ OK - no variables bound
match value
case !Positive => "not positive"

// ❌ Error - x is not available in the branch
match option
case !(Some(x)) => x  // x not bound when Some(_) doesn't match
```

**Rationale:** Since a not-pattern succeeds when its nested pattern fails, any variables that would be bound by the nested pattern have no meaningful values to bind.

### Guard Pattern `if e`

No variables are bound.

```jo
match value
case x if x > 0 =>
  // x is bound by variable pattern, not guard
  x
end
```

### Assignment Pattern `then x = e1, y = e2, ...`

The assignment identifiers `x`, `y`, etc. are all definitely bound. These identifiers must reference pattern parameters defined in the pattern definition (not new bindings).

```jo
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0  // n is definitely bound
  case Cons(_, tail) then n = 1 + tail.size  // n is definitely bound
end
```

### Literal Pattern

No variables are bound.

```jo
match value
case 0 => "zero"
case 1 => "one"
```

## See Also

- [Pattern Forms](pattern-forms.md) - Basic pattern types and composition
- [Pattern Definitions](../definitions/pattern-definitions.md) - Reusable patterns
