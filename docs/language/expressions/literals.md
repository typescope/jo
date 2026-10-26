# Literals

Literals are constant values written directly in source code.

## Grammar

```
integer     ::= ["-"] (decimal | hexadecimal)
decimal     ::= digit {digit | "_" digit}
hexadecimal ::= "0" ("x" | "X") hex_digit {hex_digit | "_" hex_digit}
float       ::= ["-"] (decimal "." digit {digit | "_" digit} [exponent] | decimal exponent)
exponent    ::= ("e" | "E") ["+" | "-"] digit {digit | "_" digit}
boolean     ::= "true" | "false"
character   ::= "'" <character> "'"
string      ::= <single-line-string> | <multi-line-string>
```

Underscores may appear between digits for readability (`1_000_000`, `0xFF_FF`). See [Syntax Summary](../syntax/syntax-summary.md) for the full underscore rules.

Examples: `42`, `0xFF`, `3.14`, `-17`, `6.022e23`, `true`, `'a'`, `"hello"`

## Integer Literals

Decimal integer literals default to `Int` and must be in the range
`-2147483648..2147483647`. When `Long` is expected, the range is
`-9223372036854775808..9223372036854775807`. Out-of-range literals are rejected
at compile time. Calling `.toLong` on a literal does not give it a `Long`
expected type. Use a type annotation or ascription for larger values:

```jo
val big: Long = 4294967296
val bigger = 4294967297 as Long
```

Hexadecimal literals describe two's-complement bit patterns, with up to eight
hex digits for `Int` or sixteen when `Long` is expected. For example,
`0xFFFFFFFF` is `-1` as an `Int`.

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

Character literals represent Unicode code points in the range U+0000 to U+10FFFF, excluding surrogate code points U+D800 to U+DFFF.

This includes emojis and other characters beyond the Basic Multilingual Plane:

```jo
val letter = 'a'
val digit = '9'

// Emojis and characters beyond U+FFFF
val smiley = '😀'    // U+1F600 = 128512
val rocket = '🚀'    // U+1F680 = 128640
val heart = '❤'      // U+2764 = 10084

// Can also use integer literals for Char type
val emoji: Char = 0x1F600  // Same as '😀'

// Invalid: surrogate code points are rejected
// val invalid: Char = 0xD800  // Error: surrogate code point
```

**Supported escape sequences:**

| Escape | Character | Description |
|--------|-----------|-------------|
| `\b` | U+0008 | Backspace |
| `\f` | U+000C | Form feed |
| `\n` | U+000A | Newline (line feed) |
| `\r` | U+000D | Carriage return |
| `\t` | U+0009 | Horizontal tab |
| `\'` | U+0027 | Single quote |
| `\\` | U+005C | Backslash |

```jo
val tab = '\t'
val quote = '\''
val backslash = '\\'
```

## String Literals

```jo
val message = "Hello, World!"
val multiline = """
  This is a
  multiline string
  """
```

**Supported escape sequences in single-line strings:**

| Escape | Character | Description |
|--------|-----------|-------------|
| `\b` | U+0008 | Backspace |
| `\f` | U+000C | Form feed |
| `\n` | U+000A | Newline (line feed) |
| `\r` | U+000D | Carriage return |
| `\t` | U+0009 | Horizontal tab |
| `\"` | U+0022 | Double quote |
| `\\` | U+005C | Backslash |
| `\u{XXXX}` | Variable | Unicode code point (1-6 hex digits) |

```jo
val newline = "First line\nSecond line"
val quote = "She said \"Hello\""
val backslash = "Path: C:\\Users"
val emoji = "Smiley: \u{1F600}"  // 😀
```

**Note:** Multiline strings (triple-quoted `"""..."""`) only support `\u{...}` Unicode escapes, not other escape sequences.

For multiline strings and string interpolation, see [String Literals](string.md).

## List Literals

`"[" [expr {"," expr}] "]"`

List literals create immutable sequences. They support splicing with `..` to spread elements from other lists:

```jo
[1, 2, 3]
["hello", "world"]
[]  // empty list

// Nested lists
[[1, 2], [3, 4]]

// Mixed with expressions
[x + 1, y * 2, z]

// Splicing: spread elements from other lists
val l1 = [1, 2]
val l2 = [5, 6]
val combined = [..l1, 3, 4, ..l2]  // [1, 2, 3, 4, 5, 6]

// Multiple splices
val mixed = [0, ..l1, 10, ..l2, 20]  // [0, 1, 2, 10, 5, 6, 20]
```


## See Also

- [Expression Forms](expression-forms.md) - Atoms and word sequences
- [Syntax Summary](../syntax/syntax-summary.md) - Complete grammar
