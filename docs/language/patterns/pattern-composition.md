# Pattern Composition

Patterns can be combined using operators and nesting to create complex matching logic.

## Expression Patterns

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

## Or Patterns

**Syntax:** `pattern₁ | pattern₂`

Matches if either `pattern₁` or `pattern₂` matches. Both patterns must bind the same set of variables with compatible types.

Defined in `Predef.jo` using the infix pattern operator `|[T]`.

```jo
match x
case 0 | 1 | 2 => "small"
case 3 | 4 | 5 => "medium"
case _ => "large"
end

match token
case Ident(name) | Keyword(name) | Operator(name) =>
  // name is bound in all branches
  println(name)
end
```

### Uniform Binding Requirement

All branches must bind exactly the same set of variables:

```jo
// ✓ OK - both bind x
match either
case Left(x) | Right(x) => x
end

// ❌ Error - Left binds x, Right binds y
match result
case Left(x) | Right(y) => ...
end

// ❌ Error - first binds x and y, second binds only x
match result
case Pair(x, y) | Single(x) => ...
end
```

!!!note "Design Rationale: Uniform Binding Requirement"
    OR-patterns require all branches to bind the same variables (not just a common subset) to prevent accidental errors. If we allowed different sets, forgetting to bind a variable in one branch would go unnoticed—the variable would simply be excluded from the common set. By requiring uniform bindings, the compiler catches these mistakes immediately.

## And Patterns

**Syntax:** `pattern₁ & pattern₂`

Matches if both `pattern₁` and `pattern₂` match the scrutinee. Both patterns match against the same value.

Defined in `Predef.jo` using the infix pattern operator `&[T]`.

```jo
match n
case Positive & Even => "positive even number"
case Positive => "positive odd number"
case _ => "not positive"
end
```

### Variable Binding in And-Patterns

A variable is definitely bound if it is definitely bound in either `pattern₁` or `pattern₂`. It is an error if a variable is definitely bound in both patterns.

```jo
// ✓ OK - x bound in first pattern, y in second
case (x, _) & (_, y) => ...

// ❌ Error - x bound in both patterns
case Some(x) & Just(x) => ...
```

## Parenthesized Patterns

**Syntax:** `(pattern)`

Groups a pattern. Useful for:
- Controlling precedence
- Adding guards to nested patterns

```jo
// Control precedence
match x
case (0 | 1) & Positive => ...  // (0 | 1) grouped
end

// Guard on nested pattern
match x
case Some((y if y > 0)) => "some positive"
case Some(_) => "some non-positive"
case None => "none"
end

// Complex composition
match result
case (Ok(x) | Err(x)) & (y if y.isValid) =>
  process(y)
end
```

## Nested Patterns

Patterns can be nested arbitrarily deep:

```jo
// Nested apply patterns
match tree
case Branch(Branch(Leaf(x), _, _), _, Leaf(y)) =>
  x + y
end

// Nested with guards
match data
case Some(User(name, age)) if age >= 18 =>
  "Adult: " + name
end

// Deeply nested structures
match json
case Object([
  ("name", String(name)),
  ("age", Number(age)),
  ("address", Object(fields))
]) =>
  processUser(name, age, fields)
end
```

## Pattern Operators in Context

All pattern operators have the same precedence and are left-associative:

```jo
// And (&) has higher precedence than Or (|)
case A | B & C =>
  // Parsed as: A | (B & C)

// Use parentheses for clarity
case (A | B) & C =>
  // Explicitly: (A | B) & C

// Multiple operators
case A | B | C & D =>
  // Parsed as: A | B | (C & D)
```

## Complex Composition Examples

### Combining Or and And

```jo
match status
case (Success | Warning) & Valid =>
  proceed()
case Error & Recoverable =>
  retry()
case Error =>
  abort()
end
```

### Nested Patterns with Guards

```jo
match tree
case Node(left @ Node(_, _), value, _) if value > 0 =>
  // left is bound and known to be a Node
  // value is bound and known to be positive
  processNode(left, value)
end
```

### Pattern Definitions in Composition

```jo
pattern Positive: Int =
  case x if x > 0

pattern Even: Int =
  case x if x % 2 == 0

// Use in composition
match n
case Positive & Even => "positive even"
case Positive => "positive odd"
case Even => "non-positive even"
case _ => "non-positive odd"
end
```

### Sequence Patterns with Composition

```jo
match list
case [head, ..tail] & Size(n) if n > 10 =>
  // head and tail are bound from sequence pattern
  // n is bound from Size pattern
  "Large list starting with " + head
end
```

## Type Decomposition Patterns

Pattern definitions can be used to decompose a type into a complete, disjoint partition by mapping values to a union type:

```jo
// Define the partition categories
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
  end
```

### Classifying by Sign

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
  end
```

## Best Practices

### Clarity Over Cleverness

Prefer clear patterns over complex compositions:

```jo
// Complex - hard to understand
case (A | B) & (C | D) & E if condition => ...

// Better - split into multiple cases
case A & C & E if condition => ...
case A & D & E if condition => ...
case B & C & E if condition => ...
case B & D & E if condition => ...
```

### Use Pattern Definitions

Extract complex patterns into named definitions:

```jo
// Define once
pattern ValidConfig: Config =
  case c & match c.host with Some(_) & match c.port with Some(_)

// Use multiple times
match config
case ValidConfig => proceed()
case _ => abort()
end
```

### Exhaustiveness

Leverage the type system for exhaustive checking:

```jo
// Compiler verifies all cases covered
match status: Status
case Success => ...
case Warning => ...
case Error => ...
end  // No default needed - exhaustive
```

## See Also

- [Pattern Forms](pattern-forms.md) - Basic pattern building blocks
- [Semantics](semantics.md) - Pattern matching rules
- [Pattern Definitions](pattern-definitions.md) - Reusable patterns
