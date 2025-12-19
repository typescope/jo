# Pattern Language

This document specifies the pattern language used in `match` expressions and pattern definitions.

## Syntax

```
pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern = "if" expr
assign_pattern = "then" assignment {"," assignment}
assignment = ident "=" expr

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern
               | qualid
               | type_pattern
               | bind_pattern
               | apply_pattern
               | "(" pattern ")"
               | sequence_pattern
               | nested_match_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
bind_pattern = ident "@" simple_pattern
apply_pattern = qualid "(" [pattern {"," pattern}] ")"
nested_match_pattern = "match" expr "with" simple_pattern
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

### Bind Patterns

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
case Some(config) & match config.database.host with Some(host) => connect(host)
end
```

### Guard Patterns

**Syntax:** `pattern if condition`

A guard adds a boolean condition to a pattern. The pattern matches only if both the pattern matches and the condition evaluates to `true`. The condition can reference variables bound by the pattern.

```jo
match x
case n if n > 0 => "positive"
case n if n < 0 => "negative"
case _ => "zero"
end
```

### Nested Match Patterns

**Syntax:** `match word with simple_pattern`

Evaluates a term expression and matches the result against `simple_pattern`. The original scrutinee is ignored. Enables matching on method calls, field accesses, and arbitrary expressions.

**Motivation:** Nested match patterns address two common scenarios that would otherwise require deeply nested match expressions:

1. **Matching multiple values/scrutinees**: When you need to match against multiple values simultaneously, nested match patterns combined with AND patterns provide a flat syntax.

2. **Matching on method results**: When you need to call a method on a matched value and then match on the result.

```jo
// Match multiple values (without nested match: deeply nested and verbose)
match x
case Some(a) & match y with Some(b) => combine(a, b)
case _ => useDefault()
end

// Match on field access
match configOpt
case Some(config) & match config.database.host with Some(host) => connect(host)
case _ => useDefault()
end

// Match on method call
match requestOpt
case Some(req) & match req.getAuthHeader() with Some(token) =>
  authenticate(token)
case _ => reject()
end
```

The `match ... with ...` keywords disambiguate the term expression from pattern syntax, allowing arbitrary expressions including method calls.

### Assignment Patterns

**Syntax:** `then x = expr, y = expr2, ...`

Assignment patterns allow binding computed values to pattern parameters. They are primarily used in pattern definitions to extract and compute values from the matched data structure. They naturally follow guard patterns when both are present.

**Semantics:**

- The assignments are executed in sequence
- Each assignment evaluates the expression and binds the result to the corresponding pattern parameter
- The pattern parameter identifiers must already be defined in the pattern definition parameters

**Example:**

```jo
// Pattern definition with computed size parameter
pattern Size[T](n: Int): List[T] =
  case Nil then n = 0
  case Cons(_, tail) then n = 1 + tail.size
  case Append(prefix, _) then n = prefix.size + 1
  case Concat(lhs, rhs) then n = lhs.size + rhs.size
end

// Use the pattern
match myList
case Size(n) if n > 10 => "large list"
case Size(n) => "small list"
end
```

In this example, the `Size` pattern has a parameter `n` that represents the computed size of the list. Each case matches a different list constructor and uses `then n = ...` to assign the computed size to the parameter `n`.

**Multiple assignments:**

```jo
pattern Stats[T](count: Int, sum: Int): List[Int] =
  case Nil then count = 0, sum = 0
  case Cons(x, tail) then count = 1 + tail.count, sum = x + tail.sum
end
```

**Combining with guards:**

```jo
pattern LargeList[T](n: Int): List[T] =
  case Cons(_, tail) if tail.size > 99 then n = 1 + tail.size
end
```

## Pattern Composition

### Expression Patterns

**Syntax:** `simple_pattern {simple_pattern}`

A sequence of simple patterns juxtaposed without operators. The interpretation depends on the pattern context—typically used for applying infix pattern operators.

**Note:** Pattern expressions use the same precedence and associativity rules as term expressions and type expressions. This unified approach ensures consistent parsing across all expression contexts in the language.

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

!!! note "Local Reasoning Design Principle"
    Pattern matching uses a single flat flow typing scope per case. Nested patterns do not introduce nested scopes—all variables bound within a case pattern share the same scope and flow from left to right. This enables local reasoning by eliminating scope nesting complexity.

    Pattern parameters in pattern definitions are an exception: they are predefined in the flow scope for each case, allowing them to be referenced throughout the pattern body.

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

- **Bind pattern** `x @ p`:

    The variable `x` is definitely bound. It is an error if `x` is already definitely bound. Other variables are definitely bound according to pattern `p`.

- **Apply pattern** `C(p₁, ..., pₙ)`:

    A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

- **Sequence pattern** `[p₁, ..., pₙ]`:

    A variable is definitely bound if it is definitely bound in any of the nested patterns `pᵢ`. It is an error if a pattern variable is definitely bound in more than one nested pattern.

- **Or-pattern** `p₁ | p₂`:

    All branches must bind exactly the same set of variables. A variable is definitely bound after the or-pattern if it is bound in all branches.

    !!! note "Design Rationale: Uniform Binding Requirement"
        OR-patterns require all branches to bind the same variables (not just a common subset) to prevent accidental errors. If we allowed different sets, forgetting to bind a variable in one branch would go unnoticed—the variable would simply be excluded from the common set. By requiring uniform bindings, the compiler catches these mistakes immediately.

- **And-pattern** `p₁ & p₂`:

    A variable is definitely bound if it is definitely bound in `p₁` or `p₂`. It is an error if a pattern variable is definitely bound in both `p₁` and `p₂`.

- **Guard pattern** `if e`:

    No variables are bound.

- **Nested match pattern** `match e with p`:

    A variable is definitely bound if it is definitely bound in pattern `p`.

- **Assignment pattern** `then x = e1, y = e2, ...`:

    The assignment identifiers `x`, `y`, etc. are all definitely bound. These identifiers must reference pattern parameters defined in the pattern definition (not new bindings).

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
case Some(config) & match config.database.host with Some(host) =>
  connect(host)  // config and host are both bound
end
```

In this example, `config` is definitely bound by `Some(config)`, making it available for use in the nested match pattern `match config.database.host with Some(host)`.

**Error: Inconsistent bindings in OR-pattern**

```jo
// ERROR: Left binds x, Right binds y - different variables
match result
case Left(x) | Right(y) => ...
end

// ERROR: Left binds x and y, Right binds only x - missing y in second branch
match result
case Left(x, y) | Right(x) => ...
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
  case u & match u.name with name & match u.age with age if age >= 18

// Use in match expressions
match user
case ValidUser(name, age) => "Welcome, " + name
case _ => "Invalid user"
end
```

### Parameter Binding Rule

Each pattern parameter must be definitely bound in each case of the pattern definition.

**Note:** Pattern definitions can have both parameters and multiple cases. Each case must independently ensure all parameters are definitely bound.

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

### Type Decomposition Patterns

Pattern definitions can be used to decompose a type into a complete, disjoint partition by mapping values to a union type. This technique enables exhaustive pattern matching on types that don't naturally support it (like `Int` or `String`).

**Example: HTTP Status Code Classification**

```jo
// Define the partition categories as a union type
union HttpStatus = Success | Redirect | ClientError | ServerError

// Map Int values to the union type
pattern HttpStatus(v: HttpStatus): Int =
  case x then v = begin
    if x >= 200 && x < 300 then Success
    else if x >= 300 && x < 400 then Redirect
    else if x >= 400 && x < 500 then ClientError
    else ServerError
  end

// Use in pattern matching - exhaustiveness is guaranteed
def classify(code: Int): String =
  match code
    case HttpStatus Success => "Success"
    case HttpStatus Redirect => "Redirect"
    case HttpStatus ClientError => "Client Error"
    case HttpStatus ServerError => "Server Error"
```

**Example: Classifying Integers by Sign**

```jo
union Signed = Neg | Zero | Pos

pattern Signed(v: Signed): Int =
  case x then v = begin
    if x > 0 then Pos
    else if x == 0 then Zero
    else Neg
  end

def sign(x: Int): String =
  match x
    case Signed Neg => "negative"
    case Signed Zero => "zero"
    case Signed Pos => "positive"
```

This pattern works for any type where you can define a total mapping to a union type, enabling domain-specific partitions with compile-time verification.

## Examples

```jo
// Nested structures with guards
match tree
case Node(left @ Node(_, _), value, _) if value > 0 => ...
end

// Or-patterns with multiple alternatives
match token
case Ident(name) | Keyword(name) | Operator(name) => name
end

// Nested match
match configOpt
case Some(config) & match config.database.host with Some(host) => connect(host)
case _ => useDefault()
end

// And-patterns for multiple constraints
match n
case Positive & Even => ...
case _ => ...
end
```
