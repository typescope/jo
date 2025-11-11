# Variable Arguments (Varargs)

Jo supports variable arguments (varargs), allowing functions to accept a flexible number of arguments of the same type. This feature is useful for functions like `sum`, `avg`, or `List` that naturally work with varying numbers of inputs.

## Syntax

### Declaring Vararg Parameters

A vararg parameter is declared using the `..` prefix before the type:

```jo
def f(x: ..T): U = ...
```

**Rules:**

- A vararg parameter must be the last parameter in the parameter list
- Only one vararg parameter is allowed per function
- The vararg parameter receives the arguments as a `List[T]`

### Examples

```jo
// Function accepting variable number of integers
def sum(numbers: ..Int): Int =
  numbers.fold 0 (acc, n) => acc + n

// Function accepting variable number of strings
def printAll(messages: ..String): Unit =
  messages.foreach (msg => println msg)
```

## Calling Vararg Functions

### Direct Arguments

Pass multiple arguments directly, separated by commas or spaces:

```jo
sum(1, 2, 3, 4, 5)        // Passing 5 integers
printAll "First" "Second" "Third"  // Passing 3 strings
```

### Spreading Lists

Use the spread operator `..` to expand a list into individual arguments:

```jo
val numbers = List(3, 5, 6, 8)
sum(1, 2, ..numbers, 9, 10)  // Expands to: sum(1, 2, 3, 5, 6, 8, 9, 10)
```

The spread operator can be used:

- At the beginning: `sum(..list)`
- In the middle: `sum(1, 2, ..list, 9, 10)`
- At the end: `sum(1, 2, ..list)`
- Multiple times: `sum(..list1, ..list2, 42)`

## Implementation Details

When a function with vararg parameters is called:

1. All arguments (including spread lists) are collected
2. They are combined into a single `List[Type]`
3. The function receives this list as the vararg parameter

For example:
```jo
def sum(numbers: ..Int): Int = ...

// Inside sum, 'numbers' is a List[Int]
sum(1, 2, 3)  // numbers = List(1, 2, 3)
```

## List Construction with Spread

The spread operator is particularly useful when constructing lists:

```jo
val original = List(3, 5, 6, 8)
val extended = [1, 2, ..original, 9, 10]
// Result: List(1, 2, 3, 5, 6, 8, 9, 10)
```

This allows for efficient list concatenation and manipulation.

## Common Use Cases

### 1. Aggregation Functions

```jo
def max(numbers: ..Int): Int =
  numbers.fold (numbers.head) (a, b) => if a > b then a else b

max 5 2 8 1 9 3  // Returns 9
```

### 2. Collection Construction

```jo
// Standard library List constructor
val list1 = List(1, 2, 3)
val list2 = List "hello" "world"
```

### 3. Logging and Output

```jo
def log(messages: ..String): Unit =
  messages.foreach (msg => println msg)

log "Error:" "File not found" "/path/to/file"
```

## How It Works

Unlike many languages where varargs are special syntax, in Jo the `..` notation is implemented as ordinary names defined in the standard library:

```jo
// Used to indicate the last parameter is a vararg
type ..[T] = List[T]

// Splice a list as flat arguments
def ..[T](l: List[T]): T = abort "Only support unpacking in argument positions"
```

The key insights:

- `..T` is just a type alias for `List[T]`
- The spread operator `..expr` is a function call that is specially handled by the compiler
- This design makes varargs feel like native syntax while being library-defined

## Restrictions

1. **Position**: Vararg parameter must be the last parameter
   ```jo
   // Valid
   def f(x: Int, y: Int, rest: ..Int): Int = ...

   // Invalid - vararg must be last
   def g(rest: ..Int, x: Int): Int = ...
   ```

2. **Count**: Only one vararg parameter per function
   ```jo
   // Invalid - multiple varargs
   def h(xs: ..Int, ys: ..String): Unit = ...
   ```

3. **Spread in list context**: The spread operator `..` can only be used:
   - In function calls with vararg parameters
   - In list literals `[...]`

   It cannot be used in arbitrary expressions.
