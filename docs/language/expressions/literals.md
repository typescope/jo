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

For detailed information about string literals, see:

- [Multiline Strings](multiline-string.md)
- [String Interpolation](string-interpolation.md)

## List Literals

`"[" [term {"," term}] "]"`

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

## Map and Set Literals

`"{" [collection_elem {"," collection_elem}] "}"`

Map and set literals use curly braces `{}` and are disambiguated based on **element syntax**:

### Disambiguation Rules

1. **All elements with `:`** → Map literal (map pairs: `key: value`)
2. **No elements with `:`** → Set literal
3. **Mixed elements** → Compile error
4. **Empty `{}`** → Type-directed (defaults to immutable Map)

### Map Literals

Map literals create key-value mappings. Each element must use the `:` colon syntax for pairs:

```jo
// Immutable maps (default)
{"a": 1, "b": 2, "c": 3}
{"name": "Alice", "age": 30}


// Empty map (requires type annotation for non-Map types)
val empty: Map[String, Int] = {}
val defaultEmpty = {}  // Error: cannot infer type

// Nested maps
{"outer": {"inner": 100}}

// Mixed with expressions
{name: getName(), age: getAge()}

// Accessing map elements
val m = {"a": 1, "b": 2}
println m["a"]
```

### Set Literals

Set literals create collections of unique elements. Elements must **not** use the `~` operator:

```jo
// Immutable sets (default)
{1, 2, 3}
{"apple", "banana", "cherry"}

// Empty set (requires type annotation)
val empty: Set[String] = {}

// Set operations
val s = {1, 2, 3}
println(s.contains(2))  // true
println(s.size)         // 3
```

### Mutable vs Immutable

By default, `{}` literals create **immutable** collections. To create **mutable** collections, use explicit type annotations:

```jo
// Immutable (default)
val im = {"a": 1}  // jo.Map.Map[String, Int]
val is = {1, 2, 3}  // jo.Set.Set[Int]

// Mutable (requires import and type annotation)
import jo.mutable.Map.Map
import jo.mutable.Set.Set

val mm: Map[String, Int] = {"a": 1}  // jo.mutable.Map.Map[String, Int]
val ms: Set[Int] = {1, 2, 3}          // jo.mutable.Set.Set[Int]
```

### Error Cases

```jo
// Error: mixing pairs and non-pairs
{1, "a": 2}  // Compile error: Cannot mix map pairs and regular elements

// Error: mixing pairs and non-pairs
{"a": 1, 2}  // Compile error: Cannot mix map pairs and regular elements

// Error: empty literal without type annotation
val x = {}  // Compile error: cannot infer type
```

## See Also

- [Words](words.md) - Overview of word forms
- [Syntax Summary](../syntax-summary.md) - Complete grammar
