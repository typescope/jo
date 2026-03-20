# String Interpolation

String interpolation allows programmers to embed expressions within string literals using the `\{...}` syntax. This feature works in both single-line strings (`"..."`) and multiline strings (`"""..."""`).

## Basic Usage

### Simple Interpolation

```jo
val name = "Alice"
val age = 25
val message = "Hello \{name}, you are \{age} years old!"
// Produces: "Hello Alice, you are 25 years old!"
```

### Expressions in Interpolation

Any single-line expression can be used within `\{...}`:

```jo
val x = 10
val y = 20
val result = "Sum: \{x + y}"  // Produces: "Sum: 30"
```

## Type Conversion

Interpolated expressions are automatically converted to `String` using parameter adapters. The compiler provides default adapters for common types:

```jo
val count = 42
val message = "The count is \{count}"  // Int automatically converted to String
```

### Standard Adapters

The standard library provides default adapters for common types:

- `boolToStr` for `Bool`
- `.toString` for any type with a `toString` method

### Custom Types

If no adapter is available, a compile-time error is reported:

```jo
val custom = MyType()
val message = "Value: \{custom}"  // Error if no adapter exists
```

To support interpolation for custom types, define a `toString` method:

```jo
class Person(name: String, age: Int)
  def toString: String = "\{name} (\{age})"
end

val alice = Person("Alice", 25)
val message = "Person: \{alice}"  // OK: uses .toString adapter
```

::: info

In the future, we may support custom adapters for string interpolation by
profiting the adapters from the adapation context if the expected type is
`String`. For example, given the following definition

    def typeToStr(tp: Type): String = ...
    def code(s: String with [typeToStr]): String = s

We can write:

    code "found = \{tp}, expect = \{expectType}"

It type checks because in checking the interpolation, the adaptation context
contains `typeToStr`.

There is no worry about intention here, as the context is immediate and the
only valid reason for the presence of an adaptation context with an expected
type `String` is to tweak the adaptation of interpolation.
:::
## Interpolation in Multiline Strings

Interpolation works seamlessly with multiline strings:

```jo
val name = "Alice"
val age = 25
val profile = """
    Name: \{name}
    Age: \{age}
    Status: active
    """
```

### Indentation Rules

The interpolated expressions must respect the base indentation of the multiline string. If an interpolation starts a line, it must have at least the base indentation:

```jo
val value = 42
val text = """
    Result:
    \{value}  // OK: has required indentation
    """
```

Insufficient indentation is an error:

```jo
val value = 42
val text = """
    Result:
\{value}  // Error: insufficient indentation
    """
```

## Escaping

To include a literal `\{` in a string, escape the backslash with `\\{`:

```jo
val text = "This is not interpolated: \\{name}"
// Produces: "This is not interpolated: \{name}"
```

In multiline strings, where most escape sequences are treated as literal, `\\{` still escapes the interpolation:

```jo
val code = """
    This is literal: \\{variable}
    """
// Produces: "This is literal: \{variable}"
```

## Restrictions

### Single-line Expressions

Interpolations cannot span multiple lines. This restriction applies to both single-line and multiline strings:

```jo
// ERROR in single-line string
val message = "Result: \{x +
                        y}"  // Error: interpolation spans multiple lines

// ERROR in multiline string
val text = """
    Result: \{x
              + y}  // Error: interpolation spans multiple lines
    """
```

The restriction ensures that interpolations remain simple and readable, and simplifies parsing.


### Type Constraints

The interpolated expression must be convertible to `String` via an adapter:

```jo
val file = FileHandle()
val message = "File: \{file}"  // Error if no adapter exists for FileHandle
```

## Implementation Details

### Syntax

The syntax uses `\{` to start interpolation and `}` to end it:

```
interpolated-string ::= '"' (string-content | interpolation)* '"'
                      | '"""' (string-content | interpolation)* '"""'
interpolation ::= '\{' expression '}'
```

### Desugaring

Interpolated strings are transformed during type checking into a series of string concatenations using the `+` operator:

```jo
"Hello \{name}!"
// Becomes equivalent to:
"Hello " + name + "!"
```

For non-String types, the compiler searches for an adapter and applies the conversion:

```jo
"Count: \{42}"
// Becomes equivalent to:
"Count: " + 42.toString
```

### Type Checking Process

1. Parse the string into parts (literals and interpolations)
2. Type check each interpolated expression with the target type `String`
3. For each expression:

    - If the type conforms to `String`, use it directly
    - Otherwise, search for an adapter from the default list: `[boolToStr, ".toString"]`
    - If no conversion found, report error

4. Concatenate the string parts together

### AST Representation

During parsing, interpolated strings are represented as:

```scala
case class InterpolatedString(parts: List[Word])(val span: Span) extends Word
```

Where `parts` alternates between string literals and expressions.

After type checking, the `InterpolatedString` is transformed into a tree of `Select` and `Apply` nodes representing the concatenation.

## Examples

### Example 1: Simple Message

```jo
val name = "Alice"
val greeting = "Hello \{name}!"
println greeting
// Output: Hello Alice!
```

### Example 2: Arithmetic

```jo
val x = 10
val y = 20
val sum = "Sum: \{x} + \{y} = \{x + y}"
println sum
// Output: Sum: 10 + 20 = 30
```

### Example 3: Multiline with Interpolation

```jo
val name = "Alice"
val age = 25
val description = """
    Name: \{name}
    Age: \{age}
    Status: active
    """
println description
// Output:
//     Name: Alice
//     Age: 25
//     Status: active
```

### Example 4: Escape Sequences

```jo
val name = "Alice"
val escaped = "This is not interpolated: \\{name}"
println escaped
// Output: This is not interpolated: \{name}
```

### Example 5: Custom Type Conversion

```jo
class Point(x: Int, y: Int)
  def toString: String = "(\{x}, \{y})"
end

val origin = Point(0, 0)
val message = "Origin: \{origin}"
println message
// Output: Origin: (0, 0)
```

## Design Rationale

### Choice of `\{...}` Syntax

The `\{...}` syntax was chosen for several reasons:

1. **Consistency**: Uses backslash like other escape sequences
2. **Visual clarity**: The `\{` clearly marks interpolation start
3. **No conflicts**: Doesn't interfere with existing syntax
4. **Raw strings**: Works well with multiline raw string semantics

### Single-line Restriction

Interpolations are restricted to single lines because:

1. **Readability**: Multi-line expressions in strings are hard to read
2. **Simplicity**: Parser implementation is simpler
3. **Best practice**: Encourages extracting complex expressions to variables
4. **Consistency**: Matches the design of most string interpolation features
