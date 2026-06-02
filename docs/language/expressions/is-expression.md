# Is Expression

The `is` expression tests whether a value matches a pattern. It evaluates to `true` on
success and `false` on failure. Variables bound by the pattern are available through
flow typing in `if`/`while` conditions and `&&`/`||` expressions.

## Syntax

```
is_expression = atom "is" simple_pattern
```

## Semantics

The `is` expression evaluates as follows:

1. Evaluate the word (scrutinee) to a value `v`
2. Attempt to match `v` against `simple_pattern`
3. Return `true` if the pattern matches, `false` otherwise

## Flow Typing

Flow typing tracks which pattern variables are definitely bound at each point in an
expression. It applies to:

- The conditions of `if`/`while`
- Word sequences joined by `&&`, `||`, or `!`

Variables bound by `is` expressions become available in subsequent code through
flow typing. This works both in control flow constructs (`if`, `while`) and in
boolean expressions (`&&`, `||`).

### Flow Expression

Similar to pattern-level flow typing, expression-level flow typing uses a flat scope
that evolves along a _flow expression_.  A flow expression is defined as follows:

- An is-expression is a flow expression
- Two words joined by `||` or `&&` is a flow expression
- A boolean tree negated by `!` is a flow expression

Generally, the names bound in a flow expression will not be available outside
the flow expression.

However, the bound names in the condition of `if/else` and `while` will be
available in typing the body of `if/then` and `while`.

### Flow Scope

The starting point of a flow typing will create a flow scope `sc` which maintains

- A set of definitely bound pattern variables
- A map of introduced pattern name to their symbols

Unlike traditional lexical scope, a flow scope creates bindings that
progressively become available in typing latter parts of an expression in the
same lexical scope.

The flow scope is flat -- pattern variables of a flow scope are not available in
the pattern name universe of a nested flow scope. The flatness facilitates local
reasoning about flow typing.

Flow scope primarily concerns pattern variables:

- For a binding in patterns, the name is first searched in the pattern universe of the flow scope.
- If absent, a fresh variable is introduced to the pattern universe of the flow scope.
- The variable becomes definitely bound at the point.

Definitely bound pattern variables are available in the expression scope:

- A nested non-flow scope captures the current state of the flow scope.
- It is an error to bind a pattern variable which is already definitely bound.

There are several things that "flow" in flow typing:

- The set of definitely bound variables in a flow scope may grow or shrink.
- The set of available pattern variables grow as flow typing progresses.

A definitely bound pattern variable in a flow scope might become unbound as
typing progresses. However, a pattern variable once created will always be
available for pattern name resolution in the same flow scope.

### Typing Rules

The following rules apply in flow typing an expression with a flow scope `sc`:

- **`e is pat`:**

    1. Type `e` with the non-flow scope derived from `sc`

         All definitely bound variables in `sc` are available in checking `e`.

         The checking goes out of flow typing and inner bindings cannot flow out.

    1. Type `pat` with `sc` and the widened type of `e` as scrutinee type

         The typing for `pat` follows flow typing for patterns.

         All definitely bound variables in `pat` are definitely bound in `sc`.

- **`lhs && rhs`:**

    1. Flow type `lhs` with `sc`
    1. Flow type `rhs` with `sc`

    ```jo
    if x is Some(y) && y > 0 then
      println(y)  // y is available
    ```

- **`lhs || rhs`:**

    1. Take a snapshot of definitely bound variables in `sc` as `snapshot`
    1. Flow type `lhs` with `sc`, and compute newly definitely bound variables `vs1`
    1. Reset definitely bound variables of `sc` to `snapshot`
    1. Flow type `rhs` with `sc`, and compute newly definitely bound variables `vs2`
    1. Remove the definitely bound variables in `vs2` if it is absent from `vs1`

    ```jo
    // Both branches bind 'value'
    if x is Some(value) || default is Some(value) then
      println(value)  // OK: value bound in both branches
    ```

- **`! e`:**

    1. Take a snapshot of definitely bound variables in `sc` as `snapshot`
    1. Flow type `e` with `sc`
    1. Reset definitely bound variables of `sc` to `snapshot`

- **otherwise**

    1. Type the expression with the non-flow scope derived from `sc`

         All definitely bound variables in `sc` are available in checking `e`.

         The checking goes out of flow typing and inner bindings cannot flow out.

These rules mirror how patterns work with `&` and `|` operators, providing consistent semantics across patterns and boolean expressions.

## Control Flow

Flow typing is used to type the condition of `if` and `while`.

**For `if` expressions:**

- Variables bound by the condition are available in the then-branch
- Bindings are NOT available in the else-branch

```jo
if x is Some(value) then
  println(value)  // value is available
else
  println("None")  // value is NOT available
```

**For `while` loops:**

- Variables bound by the condition are available in the loop body

```jo
while queue is Cons(head, tail) do
  process(head)  // head and tail are available
  queue = tail
```

### When Variables Are Not Available

Variables bound by `is` are NOT available when the expression is used in isolation (outside boolean or control flow contexts):

```jo
// No flow typing - standalone expression
val result = x is Some(y)
// result is true if x matches Some(_), false otherwise
// y is NOT available here
```

## Examples

Pattern test in an `if` condition — bound variables available in the then-branch only:

```jo
if x is Some(value) then
  println(value)   // value available here
else
  println("None")  // value NOT available here
```

Chained with `&&` — bound variables flow left to right:

```jo
if x is Some(value) && value > 0 then
  println("positive: " + value)

// Both branches of || must bind the same variables
if x is Some(v) || default is Some(v) then
  println(v)
```

Consumed in a `while` loop:

```jo
while list is Cons(head, tail) do
  process(head)
  list = tail
```
