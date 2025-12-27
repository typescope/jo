# Expression Tree Formation

This document specifies how Jo forms expression trees from sequences of words in type expressions, pattern expressions, and term expressions.

## Overview

Jo classifies expressions into three categories and applies distinct parsing strategies:

1. **Precedence expressions** - Term expressions containing only well-known precedence operators
2. **Operator expressions** - Expressions mixing words and operators, parsed strictly left-to-right
3. **Shape expressions** - Expressions without operators, parsed according to binding structure

### Scope of Application

| Expression Context | Precedence | Operator | Shape |
|-------------------|------------|----------|-------|
| Term expressions  | ✓          | ✓        | ✓     |
| Pattern expressions | ✗        | ✓        | ✓     |
| Type expressions  | ✗          | ✓        | ✓     |

**Precedence parsing is restricted to term expressions only.**

## Precedence Expressions

### Definition

A precedence expression is a term expression that satisfies all of the following conditions:

1. At least one word is an operator
2. At least one operator is a **precedence operator** (defined below)
3. ALL operators in the expression are precedence operators

An operator is any identifier matching the pattern: starts with an operator character or consists entirely of operator characters, excluding alphanumeric identifiers.

### Precedence Operators

**Definition**: The following operators are **precedence operators**, listed from lowest to highest precedence (operators bind more tightly as you go down):

| Precedence Level | Operators |
|-----------------|-----------|
| Lowest | `||` |
| ↓ | `&&` |
| ↓ | `!` |
| ↓ | `>`, `<`, `>=`, `<=`, `==`, `!=` |
| ↓ | `+`, `-` |
| ↓ | `<<`, `>>`, `\|`, `&`, `^` |
| Highest | `*`, `/`, `%` |

**Specification**: All other operators are **non-precedence operators** and cannot appear in precedence expressions.

**Associativity and Fixity**:

1. **Infix operators** are always left-associative: `a op b op c` parses as `(a op b) op c`
2. **Prefix operators** are non-associative: a prefix operator cannot be immediately followed by another prefix operator without an intervening non-operator word
3. The language does **not** assume whether a precedence operator is infix or prefix—this is determined solely by its actual usage in the expression
4. Users **may** repurpose a precedence operator (e.g., use `!` as an infix operator), but **cannot** change its precedence level relative to other operators

!!! note "Design Philosophy"
    Precedence and associativity are useful mathematical and programming conventions. However, custom operators with arbitrary precedence and associativity will undermine the convention and greatly harm readability of code.

    Therefore, Jo respects and protects that convention by defending against custom operators with arbitrary precedence and associativity. When in doubt, programmers can always make the code structure more clear and readable.

### No Mixing Rule

When at least one operator in a term expression is a precedence operator, then ALL operators in that expression MUST be precedence operators.

**Rationale**: Mixing precedence and non-precedence operators would create ambiguous parsing situations and reduce code readability.

### Examples

```jo
// Parsed as: 1 + (2 * 3)
1 + 2 * 3

// Parsed as: (x < 10) && (y > 5)
x < 10 && y > 5

// ERROR: mixing precedence operator * with non-precedence operator ++
list ++ other * 2
```

## Operator Expressions

### Definition

An operator expression is an expression that satisfies the following conditions:

1. At least one word in the expression is an operator
2. The expression is NOT a precedence expression (i.e., one of the following holds):

     - The expression is a pattern expression (precedence never applies)
     - The expression is a type expression (precedence never applies)
     - The expression is a term expression where ALL operators are non-precedence operators

### Parsing

Operator expressions are parsed strictly left-to-right without any precedence rules.

**Definition**: Operators in operator expressions have fixed arity:

- **Prefix operator**: An operator appearing at the start of an expression or immediately after another infix operator. It consumes exactly one argument to its right.
- **Infix operator**: An operator appearing between two operands. It consumes exactly one argument to its left and one argument to its right.

**Example**: In `a ++ b ++ c`:

- First `++` is infix (left operand: `a`, right operand: `b`)
- Second `++` is infix (left operand: `(a ++ b)`, right operand: `c`)
- Result: `((a ++ b) ++ c)`

### Examples

```jo
// Type expression: left-to-right, no precedence
A + B * C       // Parsed as: (A + B) * C

// Pattern expression: left-to-right, no precedence
Some x | None   // Parsed as: (Some x) | None

// Term with non-precedence operators: left-to-right
list ++ other ++ third   // Parsed as: (list ++ other) ++ third
```

## Shape Expressions

### Definition

A shape expression is an expression where NO word is an operator.

**Specification**: The tree structure is determined by the **binding structure** of each binder in the expression.

### Binding Structure

**Definition**: The binding structure of a binder (function, type constructor, or pattern predicate) is characterized by two parameters:

- **preParamCount** ∈ ℕ: Number of arguments consumed from the left (before the binder)
- **postParamCount** ∈ ℕ: Number of arguments consumed from the right (after the binder)

### Parsing

Shape expressions are parsed using a stack-based algorithm:

- Words are processed strictly left-to-right
- When a binder is encountered, it consumes arguments according to its binding structure:

    - `preParamCount` arguments from the stack (to the left)
    - `postParamCount` arguments from remaining words (to the right)

- The resulting application is pushed back onto the stack
- Non-binder words are pushed directly onto the stack

### Examples

```jo
// Type expression: List takes 1 post-argument
List Int String    // Error: List only takes 1 argument

// Pattern expression
Some (Cons head tail)    // Some takes 1 post-argument

// Term expression
map f list    // map takes 2 post-arguments
```

## Design Principles

### Design Goals

The three-strategy classification is designed to satisfy the following requirements:

1. **Familiarity**: Mathematical and programming operators (`+`, `*`, `&&`, etc.) SHALL behave according to established precedence conventions
2. **Uniformity**: Types, patterns, and terms SHALL use identical parsing algorithms where applicable
3. **Predictability**: Parsing SHALL be deterministic and unambiguous without requiring memorization of operator precedence tables
4. **Simplicity**: Programmers SHALL NOT need to define or learn custom operator precedence

### Restriction: No Custom Operator Precedence

**Design Decision**: User-defined operators CANNOT have custom precedence values.

**Justification**:

1. **Cognitive burden**: Custom precedence requires readers to memorize arbitrary rules for each codebase
2. **Readability**: Code mixing operators with different custom precedence is difficult to parse mentally
3. **Convention preservation**: Extending precedence beyond mathematical/programming conventions weakens those conventions
4. **Alternative available**: Parentheses provide explicit grouping when operator precedence is unclear

### Restriction: No Precedence in Patterns and Types

**Design Decision**: Pattern and type expressions NEVER use precedence parsing, even for built-in operators.

**Justification**:

1. **Consistency**: All non-term expressions use the same left-to-right operator parsing rules
2. **Reduced complexity**: Fewer special cases for programmers to remember
3. **Limited benefit**: Operators in patterns and types are less common; precedence provides minimal value
4. **Principle-driven**: Precedence is justified only where strong pre-existing conventions exist (arithmetic, logic)

**Example**: In types, `A + B * C` parses as `(A + B) * C`, not `A + (B * C)`, maintaining consistency with operator expressions.
