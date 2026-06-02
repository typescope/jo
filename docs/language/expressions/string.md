# String Literals

Jo provides two string literal forms: single-line strings (`"..."`) and multiline
strings (`"""..."""`). Both support string interpolation with `\{...}`.

## Single-Line Strings

```jo
val s = "Hello, World!"
val path = "C:\\Users\\Alice"
```

Escape sequences processed in single-line strings:

| Sequence | Meaning |
|---|---|
| `\n` | newline |
| `\t` | horizontal tab |
| `\"` | double quote |
| `\\` | backslash |
| `\u{X...}` | Unicode code point (1–6 hex digits) |

## Multiline Strings

Multiline strings are delimited by three or more double quotes:

```jo
val message = """
  Hello
  World
  """
```

### Indentation Stripping

The indentation level of the **closing delimiter** determines how many leading spaces
are stripped from every content line. Content lines with fewer spaces than the base
indentation (and not empty) are a compile-time error.

```jo
val text = """
    Line 1
      Line 2 (extra indent)
    Line 3
    """
// "Line 1\n  Line 2 (extra indent)\nLine 3\n"
```

### Escape Sequences

Multiline strings are **raw** — only `\u{X...}` Unicode escapes are processed. All
other backslash sequences, including `\n` and `\t`, are preserved literally.

### Extended Delimiters

Use four or more quotes to embed triple quotes in the content:

```jo
val code = """"
  let x = """hello"""
  """"
// "let x = \"\"\"hello\"\"\"\n"
```

The opening and closing delimiter must have the same quote count.

### Processing Rules

1. Collect all content lines between the opening and closing delimiter.
2. Determine base indentation from the closing delimiter column.
3. Strip the base indentation from each non-empty content line; error if insufficient.
4. Process escape sequences: only `\u{X...}` Unicode escapes are expanded; all other backslash sequences are kept as-is.
5. Join lines with newline characters. No trailing newline unless there is an empty
   line before the closing delimiter.

## String Interpolation

Both string forms support embedding expressions with `\{...}`:

```jo
val name = "Alice"
val age  = 25
"Hello \{name}, age \{age}"  // "Hello Alice, age 25"
```

### Syntax

```
interpolation ::= '\{' expression '}'
```

Expressions must fit on a single line — multi-line interpolations are a compile-time
error. To include a literal `\{`, write `\\{`.

### Type Conversion

The interpolated expression must conform to `String` or be convertible via an adapter.
The default adapter list is `[boolToStr, .toString]`. If no conversion is found, a
compile-time error is reported.

### Desugaring

An interpolated string is desugared into string concatenations during type checking:

```jo
"Hello \{name}!"
// becomes: "Hello " + name + "!"

"Count: \{42}"
// becomes: "Count: " + 42.toString
```

## Comparison

| Feature | `"..."` | `"""..."""` |
|---|---|---|
| Escape sequences | All (`\n`, `\t`, `\"`, `\\`, `\u{...}`) | Only `\u{...}` |
| Interpolation | Yes | Yes |
| Indentation stripping | No | Yes |
| Spans multiple lines | No | Yes |

## See Also

- [String Guide](../../guides/string.md) — Common tasks and worked examples
- [Literals](literals.md) — Numeric and character literals
