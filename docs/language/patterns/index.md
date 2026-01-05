# Pattern Language

This document specifies the pattern language used in `match` expressions and pattern definitions.

## Overview

Patterns enable:

- **Destructuring** - Extract values from data structures
- **Type testing** - Check and refine types
- **Conditional matching** - Add guards for additional constraints
- **Exhaustiveness checking** - Verify all cases are covered

## Syntax

```
pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern = "if" expr
assign_pattern = "then" assignment {"," assignment}

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern
               | qualid
               | type_pattern
               | bind_pattern
               | apply_pattern
               | "(" pattern ")"
               | sequence_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
bind_pattern = ident "@" simple_pattern
apply_pattern = qualid "(" [pattern {"," pattern}] ")"
sequence_pattern = "[" [expr_pattern {"," expr_pattern}] "]"
```

## Pattern Categories

### [Pattern Forms](pattern-forms.md)
The basic building blocks of patterns: literals, variables, types, bindings, applications, and sequences.

### [Pattern Composition](pattern-composition.md)
Combining patterns with operators, parentheses, and complex structures.

### [Semantics](semantics.md)
How patterns match, bind variables, and ensure type safety.

### [Pattern Definitions](pattern-definitions.md)
Defining reusable pattern functions with parameters.

## Quick Examples

```jo
// Literal patterns
match x
case 0 => "zero"
case 1 => "one"
end

// Variable patterns
match value
case x => x + 1
end

// Type patterns
match value
case x: Int => x + 1
case s: String => s.length
end

// Apply patterns (destructuring)
match option
case Some(x) => x
case None => 0
end

// Sequence patterns
match list
case [] => "empty"
case [x] => "singleton"
case [x, y] => "pair"
end

// Or patterns
match x
case 0 | 1 | 2 => "small"
case _ => "large"
end

// Guard patterns
match x
case n if n > 0 => "positive"
case n if n < 0 => "negative"
case _ => "zero"
end

// Pattern definitions
pattern Positive: Int =
  case x if x > 0

match n
case Positive => "positive number"
case _ => "not positive"
end
```

## Usage Contexts

Patterns are used in:

### Match Expressions

```jo
match value
case Some(x) => x
case None => 0
end
```

### Case Definitions

```jo
case Point(x, y) = getPoint()
// x and y are now bound
```

### For Loops

```jo
for Some(x) in optionList if x > 0 do
  println(x)
end
```

### Pattern Definitions

```jo
pattern ValidUser(name: String, age: Int): User =
  case u if u.age >= 18 then name = u.name, age = u.age
```

## See Also

- [Algebraic Data Types](../definitions/adt.md) - Types designed for pattern matching
- [Union Types](../types/union-types.md) - Union types and exhaustiveness
- [Syntax Summary](../syntax-summary.md) - Complete grammar
