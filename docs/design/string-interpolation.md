# String Interpolation

String interpolation allows you to embed expressions within string literals using the `\{...}` syntax. This feature works in both single-line strings (`"..."`) and multiline strings (`"""..."""`).

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

Interpolated expressions are automatically converted to `String` using the `Show` type class. This means you can interpolate any type that has a `Show[T]` instance defined:

```jo
val count = 42
val message = "The count is \{count}"  // Int automatically converted to String
```

### Standard Conversions

The standard library provides `Show` instances for common types:

- `Show[Byte]`
- `Show[Char]`
- `Show[Int]`

### Custom Show

If no show is available, a compile-time error is reported:

```jo
val custom = MyType()
val message = "Value: \{custom}"  // Error if no Show[MyType] exists
```

To support interpolation for custom types, define an auto instance:

```jo
data Person(name: String, age: Int)

auto def personToString: Show[Person] = p => "\{p.name} (\{p.age})"

val alice = Person("Alice", 25)
val message = "Person: \{alice}"  // OK: uses personToString
```

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

The interpolated expression must be convertible to `String` via an auto `Show[T]` instance:

```jo
val file = FileHandle()
val message = "File: \{file}"  // Error if no Show[FileHandle]
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

For non-String types, the compiler searches for an auto instance of `Show[T]` and applies the conversion:

```jo
"Count: \{42}"
// Becomes equivalent to:
"Count: " + int2String.show(42)
```

### Type Checking Process

1. Parse the string into parts (literals and interpolations)
2. Type check each interpolated expression with the target type `String`
3. For each expression:

    - If the type conforms to `String`, use it directly
    - Otherwise, search for `auto Show[T]` and apply `.show()`
    - If no conversion found, report error

4. Concatenate the strings part together

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
data Point(x: Int, y: Int)

auto pointToString: Show[Point] = p => "(\{p.x}, \{p.y})"

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

### Type Class Conversion

Using `Show[T]` for type conversion provides:

1. **Explicit conversions**: No implicit `.toString` magic
2. **Type safety**: Conversions must be explicitly defined
3. **Flexibility**: Users control how their types convert
4. **Discoverability**: Auto resolution makes conversions easy to find
