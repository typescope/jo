# Literals

Literals are constant values written directly in source code.

## Grammar

```
integer   ::= ["-"] digit {digit}
float     ::= ["-"] (digit {digit} "." digit {digit} [exponent] | digit {digit} exponent)
exponent  ::= ("e" | "E") ["+" | "-"] digit {digit}
boolean   ::= "true" | "false"
character ::= "'" <character> "'"
string    ::= <single-line-string> | <multi-line-string>
```

Examples: `42`, `3.14`, `-17`, `6.022e23`, `true`, `'a'`, `"hello"`

## Integer Literals

```jo
val count = 42
val negative = -17
val zero = 0

// Hexadecimal notation
val hex = 0xFF
val hexNegative = -0x10

// With underscores for readability
val million = 1_000_000
val billion = 1_000_000_000
```

## Float Literals

Floating-point numbers with decimal points and/or scientific notation:

```jo
val pi = 3.14159
val negative = -2.5
val small = 0.001

// Scientific notation
val avogadro = 6.022e23
val planck = 6.626e-34
val large = 1.5E10

// With underscores for readability
val precise = 3.14_159_265
val scientific = 6.022_140_76e23
```

## Boolean Literals

```jo
val flag = true
val isReady = false
```

## Character Literals

Character literals support the full Unicode range (U+0000 to U+10FFFF), including emojis and other characters beyond the Basic Multilingual Plane:

```jo
val letter = 'a'
val digit = '9'
val newline = '\n'

// Emojis and characters beyond U+FFFF
val smiley = '😀'    // U+1F600 = 128512
val rocket = '🚀'    // U+1F680 = 128640
val heart = '❤'      // U+2764 = 10084
```

## String Literals

```jo
val message = "Hello, World!"
val multiline = """
  This is a
  multiline string
  """
```

For detailed information about string literals, see:

- [Multiline Strings](multiline-string.md)
- [String Interpolation](string-interpolation.md)

## List Literals

`"[" [term {"," term}] "]"`

List literals create immutable sequences:

```jo
[1, 2, 3]
["hello", "world"]
[]  // empty list

// Nested lists
[[1, 2], [3, 4]]

// Mixed with expressions
[x + 1, y * 2, z]
```

## See Also

- [Words](words.md) - Overview of word forms
- [Syntax Summary](../syntax-summary.md) - Complete grammar
