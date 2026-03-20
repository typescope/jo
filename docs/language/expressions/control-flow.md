# Control Flow

This section defines Jo’s control-flow constructs: `if`, `match`, `return`, `while`, `for`, `break`, and `continue`.

## If

```
if ::= "if" simple_expr "then" block ["else" block] ["end"]
```

```
simple_expr ::= word {word}
```

If constructs are expressions.

```jo
if x > 0 then
  println("positive")
end

if condition then
  result1
else
  result2
end

// If expressions produce values
val status = if x > 0 then "positive" else "negative"

// Nested if
if x > 0 then
  "positive"
else if x < 0 then
  "negative"
else
  "zero"
```

## Match

```
match ::= "match" term {case} ["end"]
case ::= "case" pattern "=>" block
```

Match constructs are expressions. See [Pattern Language](../patterns/overview.md) for pattern syntax.

```jo
match value
case Some(x) => println(x)
case None => println("no value")
end

// Match expressions produce values
val result = match status
  case Success => "ok"
  case Warning => "warning"
  case Error => "error"
end

// Multiple cases
match number
case 0 => "zero"
case 1 => "one"
case x if x > 0 => "positive"
case _ => "negative"
end
```

## Return

```
return ::= "return" [expr]
```

`return` exits the enclosing function or method immediately.

```jo
def abs(n: Int): Int =
  if n < 0 then return -n
  n

def printPositive(n: Int): Unit =
  if n <= 0 then return
  println(n)
```

`return` is only valid in function/method bodies and is not allowed inside lambdas.

## While

```
while ::= "while" simple_expr "do" block ["end"]
```

While constructs are statements.

```jo
while hasNext() do
  val item = next()
  process(item)
end

var i = 0
while i < 10 do
  println(i)
  i = i + 1
end
```

### Break and Continue

`break` and `continue` are phrase-level control-flow statements:

```
break ::= "break"
continue ::= "continue"
```

- `break` exits the nearest enclosing `while`/`for` loop.
- `continue` skips to the next iteration of the nearest enclosing `while`/`for` loop.

```jo
for x in [1, 2, 3, 4, 5] do
  if x == 2 then continue
  if x == 4 then break
  println(x)
end
```

`break` and `continue` are only allowed inside `while`/`for` loops.

## For

```
for ::= "for" expr_pattern "in" simple_expr ["if" simple_expr] "do" block ["end"]
```

For loops iterate over collections by pattern matching on each element.

The syntax desugars to:

```jo
val $iter = expr.iterator
while $iter.hasNext do
  val expr_pattern = $iter.next
  if cond then
    block
```

For loops are statements.

```jo
// Basic for loop
for x in [1, 2, 3, 4, 5] do
  println(x)
end

// Pattern matching in for loop
for Point(x, y) in points do
  println("x=" + x + ", y=" + y)
end

// With filtering
for x in numbers if x > 0 do
  println(x)
end

// Multiple pattern variables
for (key, value) in pairs do
  println(key + ": " + value)
end
```

### Exhaustive Patterns

The pattern in a for loop must be exhaustive. The compiler warns about non-exhaustive patterns that could fail at runtime:

```jo
// Warning: non-exhaustive pattern (missing None)
for Some(x) in optionList do
  println(x)
end

// OK: exhaustive pattern - all lists have elements
for x in list do
  println(x)
end

// OK: filtering via guard condition
for Point(x, y) in points if x > 0 do
  println(x)
end
```

### Filtering with Is Expressions

Use the optional `if` clause to filter elements. You can also use `is` expressions in the condition:

```jo
// Filter using is expression
for elem in mixedList if elem is Some(x) do
  println(x)
end

// Combine pattern matching and filtering
for Point(x, y) in points if x > 0 && y > 0 do
  println(x + y)
end
```

::: info
Pattern match failures in for loops cause runtime errors (like pattern value definitions). Use `is` expressions in the `if` clause for filtering instead of relying on non-exhaustive patterns.
:::
