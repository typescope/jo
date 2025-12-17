# Pattern Language

This document specifies the pattern language used in `match` expressions and pattern definitions.

## Syntax

```
pattern = expr_pattern ["if" expr]
        | nested_match_pattern

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern
               | qualid
               | type_pattern
               | alias_pattern
               | apply_pattern
               | "(" pattern ")"
               | sequence_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
alias_pattern = ident "@" simple_pattern
apply_pattern = qualid "(" [pattern {"," pattern}] ")"
nested_match_pattern = qualid "~" expr_pattern
sequence_pattern = "[" [expr_pattern {"," expr_pattern}] "]"
```

## Pattern Forms

### Literal Patterns

**Syntax:** `42`, `true`, `'a'`, `"hello"`

Matches values equal to the literal. Uses structural equality for comparison.

```jo
match x
case 0 => "zero"
case 1 => "one"
end
```

### Variable Patterns

**Syntax:** `x`, `result`, `_`

Binds the scrutinee to the given identifier. Always succeeds. The identifier `_` is a wildcard that matches anything without binding.

```jo
match x
case y => y + 1  // binds x to y
case _ => 0      // matches but doesn't bind
end
```

### Type Patterns

**Syntax:** `x: Type`

Binds the scrutinee to identifier `x` with the constraint that it must have type `Type`. Performs a type test and binding.

```jo
match value
case x: Int => x + 1
case s: String => s.length
end
```

### Alias Patterns

**Syntax:** `x @ pattern`

Matches the scrutinee against `pattern` and additionally binds the entire scrutinee to `x`.

```jo
match list
case xs @ Cons(_, _) => xs.length  // bind list and match structure
end
```

### Apply Patterns

**Syntax:** `Constructor(pattern₁, ..., patternₙ)`

Matches values of algebraic data types. Extracts constructor arguments and matches them against nested patterns.

```jo
match option
case Some(x) => x
case None => 0
end
```

### Sequence Patterns

**Syntax:** `[pattern₁, ..., patternₙ]`

Matches sequences (lists, arrays) of a specific length, matching each element against the corresponding pattern.

```jo
match list
case [] => "empty"
case [x] => "singleton"
case [x, y] => "pair"
end
```

### Or Patterns

**Syntax:** `pattern₁ | pattern₂`

Matches if either `pattern₁` or `pattern₂` matches. Both patterns must bind the same set of variables with compatible types.

Defined in `Predef.jo` using the infix pattern operator `|[T]`.

```jo
match x
case 0 | 1 | 2 => "small"
case _ => "large"
end
```

### And Patterns

**Syntax:** `pattern₁ & pattern₂`

Matches if both `pattern₁` and `pattern₂` match the scrutinee. Both patterns match against the same value.

Defined in `Predef.jo` using the infix pattern operator `&[T]`.

```jo
match configOpt
case Some(config) & config.database.host ~ Some(host) => connect(host)
end
```

### Guard Patterns

**Syntax:** `if condition`

A guard filters matches based on a boolean condition. The pattern matches if the condition evaluates to `true`. The condition can reference variables bound by preceding patterns.

```jo
match x
case n if n > 0 => "positive"
case n if n < 0 => "negative"
case _ => "zero"
end
```

### Nested Match Patterns

**Syntax:** `qualid ~ expr_pattern`

Evaluates the qualified identifier (variable or field selection) and matches the result against `expr_pattern`. The original scrutinee is ignored.

Useful when apply patterns aren't available for extracting fields:

```jo
match configOpt
case Some(config) & config.database.host ~ Some(host) => connect(host)
case _ => useDefault()
end
```

To add guards to the nested pattern, use parentheses:

```jo
case Some(config) & config.database.port ~ (Some(p) if p > 1024) => "user port"
```

## Pattern Composition

### Expression Patterns

**Syntax:** `simple_pattern {simple_pattern}`

A sequence of simple patterns juxtaposed without operators. The interpretation depends on the pattern context—typically used for applying infix pattern operators.

```jo
// "x | y" is parsed as: (x) (|) (y)
// The (|) refers to the pattern operator |[T]
case x | y => ...

// "Some(x) & Positive" is parsed as: Some(x) (&) Positive
case Some(x) & Positive => ...
```

### Parenthesized Patterns

**Syntax:** `(pattern)`

Groups a pattern. Useful for:

- Controlling precedence
- Adding guards to nested patterns

```jo
match x
case Some((y if y > 0)) => "some positive"
case point ~ ((x, y) if x == y) => "diagonal point"
end
```

## Semantics

### Pattern Matching Process

Pattern matching proceeds as follows:

1. **Evaluation:** The scrutinee expression is evaluated once
2. **Sequential Testing:** Each case pattern is tested in order
3. **First Match:** The first pattern that succeeds determines which case executes
4. **Binding Scope:** Variables definitely bound in a pattern are available as term names for the corresponding case body
5. **Exhaustiveness:** The compiler warns if patterns don't cover all possible values

### Flow Typing

Each case pattern forms a single flow typing scope.

**Scoping Rules:**

- The flow typing goes from left to right, inner to outer
- A pattern variable must be bound at most once in a pattern
- A definitely bound pattern variable can be used both as term name and pattern name in later patterns
- It is an error to use a pattern variable if it is not definitely bound

**Definite Binding Rules:**

A pattern variable is definitely bound at a point in flow typing if it is definitely assigned if previous patterns are successful. The following rules define when a pattern variable is definitely bound:

- **Variable pattern** `x`:

    The variable `x` is definitely bound. It is an error if `x` is already definitely bound. It may happen if `x` is a pattern parameter and bound more than once.

- **Type pattern** `x: T`:

    The variable `x` is definitely bound. It is an error if `x` is already definitely bound. It may happen if `x` is a pattern parameter and bound more than once.

- **Alias pattern** `x @ p`:

    The variable `x` is definitely bound. It is an error if `x` is already definitely bound. Other variables are definitely bound according to pattern `p`.

- **Apply pattern** `C(p₁, ..., pₙ)`:

    A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

- **Sequence pattern** `[p₁, ..., pₙ]`:

    A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

- **Or-pattern** `p₁ | p₂`:

    A variable is definitely bound if it is definitely bound in all alternatives.

- **And-pattern** `p₁ & p₂`:

    A variable is definitely bound if it is definitely bound in `p₁` or `p₂`. It is an error if a pattern variable is definitely bound in both `p₁` and `p₂`.

- **Guard pattern** `if e`:

    No variables are bound.

- **Nested match pattern** `x ~ p`:

    A variable is definitely bound if it is definitely bound in pattern `p`.

- **Literal pattern**:

    No variables are bound.

**Error: Variable bound multiple times**

```jo
// ERROR: x is bound twice
match pair
case (x, x) => ...  // cannot bind x twice
end
```

**Valid: Variable bound once in each branch**

```jo
match either
case Left(x) | Right(x) => x  // OK: x bound once in each branch
end
```

**Example: Valid flow typing**

```jo
match configOpt
case Some(config) & config.database.host ~ Some(host) =>
  connect(host)  // config and host are both bound
end
```

In this example, `config` is definitely bound by `Some(config)`, making it available for use in the nested match pattern `config.database.host ~ Some(host)`.

**Error: Using unbound variable**

```jo
// ERROR: x is not definitely bound when used in guard
match result
case Left(x) | Right(y) if x > 10 => ...  // x might not be bound
end
```

### Type Constraints

- Pattern matching refines types based on successful matches
- Type patterns perform runtime type tests for type parameters
- The type system ensures pattern matching is type-safe

## Pattern Definitions

Patterns can be defined as reusable pattern functions using `pattern` definitions:

```jo
pattern Positive: Int =
  case x if x > 0

pattern Even: Int =
  case x if x % 2 == 0

// Use in match expressions
match n
case Positive & Even => "positive even"
end
```

### Pattern Parameters

Pattern definitions can have parameters that extract values from nested patterns. Parameter types must be explicitly specified:

```jo
pattern ValidUser(name: String, age: Int): Partial[User] =
  case u & (u.name ~ name) & (u.age ~ age) if age >= 18

// Use in match expressions
match user
case ValidUser(name, age) => "Welcome, " + name
case _ => "Invalid user"
end
```

### Parameter Binding Rule

Each pattern parameter must be definitely bound in each case of the pattern definition.

**Error: Parameter not bound**

```jo
// ERROR: parameter x is not bound in the pattern
pattern Invalid(x: Int): Option[Int] =
  case Some(y)
```

**Error: Parameter bound in only one branch**

```jo
// ERROR: parameter x is only bound in Some(x), not in None
pattern Invalid(x: Int): Option[Int] =
  case Some(x) | None
```

**Error: Parameter bound in only one case**

```jo
// ERROR: parameter x is only bound in the first case
pattern Invalid(x: Int): Option[Int] =
  case Some(x)
  case None
```

## Examples

### Complex Pattern Combinations

```jo
// Nested structures with guards
match tree
case Node(left @ Node(_, _), value, _) if value > 0 => ...
end

// Or-patterns with multiple alternatives
match token
case Ident(name) | Keyword(name) | Operator(name) => name
end

// Nested match with guards
match request
case req ~ Authorized(user) if user.isAdmin => ...
end

// And-patterns for multiple constraints
match value
case x: Int & Positive & Even => ...
end
```
