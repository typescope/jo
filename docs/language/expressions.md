# Expressions and Statements

_Draft: Work in Progress_

Jo uses a word-based syntax where expressions are sequences of words. This section covers the various forms of expressions and control flow statements.

## Basic Expressions

Jo uses a word-based syntax where expressions are sequences of words:

```jo
// Function application
println "Hello, world!"
add 1 2

// Method calls
list.append(item)
obj.method(arg1, arg2)

// Operators
1 + 2 * 3
x == y && z != w
```

## Control Flow

### Conditionals

```jo
if condition then
  action1()
else
  action2()
end

// Inline form
val result = if x > 0 then "positive" else "negative"
```

### Loops

```jo
while condition do
  action()
  updateCondition()
end
```

### Pattern Matching

```jo
match value
case #Some(x) =>
  println ("Found: " + x)
case #None =>
  println "Nothing found"
end
```

### Assignment

```jo
// Variable assignment
var counter = 0
counter = counter + 1

// Array element assignment
val arr = Array.create(10, 0)
arr[0] = 42

// Object field assignment
val config = { var timeout = 30, var retries = 3 }
config.timeout = 60
```
