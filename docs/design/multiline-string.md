# Multiline Strings

Multiline strings in Jo provide a convenient way to write string literals that span multiple lines with automatic indentation handling.

## Basic Usage

Multiline strings are delimited by triple quotes `"""` (or more):

```jo
val message = """
  Hello
  World
  """
```

This produces the string:
```
Hello
World

```

## Indentation Stripping

The indentation of the closing delimiter determines the base indentation that will be stripped from all lines:

```jo
val text = """
    Line 1
      Line 2 (extra indent)
    Line 3
    """
```

Produces:
```
Line 1
  Line 2 (extra indent)
Line 3

```

If a closing delimiter is at column 2, only 2 spaces of indentation are stripped:

```jo
val text = """
  First line
  Second line
  """
```

Produces:
```
First line
Second line

```

## Escape Sequences

Multiline strings are **raw strings** by default - most escape sequences are treated as literal characters:

```jo
val raw = """
  Line with \n literal backslash-n
  Line with \t literal backslash-t
  Path: C:\path\to\file
  """
```

Produces:
```
Line with \n literal backslash-n
Line with \t literal backslash-t
Path: C:\path\to\file

```

### Unicode Escapes

The **only** escape sequence processed in multiline strings is Unicode escape `\u{...}`:

```jo
val emoji = """
  Emoji: \u{1F600}
  """
```

Produces:
```
Emoji: 😀

```

## No Line Continuation

Unlike some languages, Jo does **not** support backslash line continuation in multiline strings. A backslash at the end of a line is treated as a literal backslash character:

```jo
val text = """
  First line\
  Second line
  """
```

Produces (note the backslash is preserved):
```
First line\
Second line

```

Multiple backslashes are also literal:

```jo
val text = """
  One: \
  Two: \\
  Three: \\\
  """
```

Produces:
```
One: \
Two: \\
Three: \\\

```

## Empty Lines

Empty lines within multiline strings are preserved:

```jo
val text = """
  Line 1

  Line 3
  """
```

Produces:
```
Line 1

Line 3

```

## Trailing Newlines

Whether a trailing newline is included depends on whether there's an empty line before the closing delimiter:

```jo
// No trailing newline
val text1 = """
  Last line
  """

// With trailing newline
val text2 = """
  Last line

  """
```

`text1` produces:
```
Last line

```

`text2` produces:
```
Last line


```

## Extended Delimiters

You can use more than three quotes to allow triple quotes within the string:

```jo
val code = """"
  let x = """hello"""
  """"
```

Produces:
```
let x = """hello"""

```

The number of opening quotes must match the number of closing quotes.

## Formal Syntax

### Lexical Structure

A multiline string literal consists of:

1. **Opening delimiter**: Three or more consecutive double-quote characters `"""` (or `""""`, `"""""`, etc.)
2. **Content**: Zero or more lines of text
3. **Closing delimiter**: The same number of double-quote characters as the opening delimiter

### Tokenization

The scanner produces the following tokens for multiline strings:

- `StringStart(n)` - Opening delimiter with `n` quote characters (where `n >= 3`)
- `StringLine(content)` - One line of raw string content (unprocessed)
- `StringEnd` - Closing delimiter

### Processing Rules

The parser processes multiline strings as follows:

1. **Collect lines**: Gather all `StringLine` tokens between `StringStart` and `StringEnd`

2. **Determine base indentation**: The indentation level of the closing delimiter determines the base indentation to strip from all content lines

3. **Strip indentation**: For each content line:
   - Count leading spaces and tabs
   - If the line has fewer than base indentation spaces and is non-empty, report an error
   - Remove the first `base_indentation` characters from the line

4. **Process escape sequences**: Apply `unescape()` with `EscapePolicy.Enable("u")`:
   - Only `\u{X...}` Unicode escapes (1-6 hex digits) are processed
   - All other backslash sequences are treated as literal characters
   - A backslash at the end of a line or string is preserved as a literal backslash

5. **Join lines**: Concatenate all processed lines with newline characters between them
   - A newline is added between consecutive lines
   - No newline is added after the last line (unless there was an empty line before the closing delimiter)

### Grammar

```
multiline-string ::= opening-delimiter content* closing-delimiter
opening-delimiter ::= '"' '"' '"'+
closing-delimiter ::= '"' '"' '"'+   (same count as opening)
content ::= <any Unicode character except the closing delimiter sequence>
```

### Escape Sequence Processing

```
escape-sequence ::= '\u{' hex-digit+ '}'
hex-digit ::= [0-9a-fA-F]
```

All other escape sequences (like `\n`, `\t`, `\"`, `\\`, etc.) are **not** processed and appear as literal characters in the resulting string.

### Indentation Error

If a non-empty content line has fewer leading spaces than the base indentation, a compile-time error is reported:

```jo
val bad = """
    Line with 4 spaces
  Line with 2 spaces  // Error: insufficient indentation
    """
```

### Empty String

A multiline string with no content lines or only empty lines produces an empty string or a string containing only newlines:

```jo
val empty = """
  """  // Empty string ""

val oneNewline = """

  """  // String containing one newline "\n"
```

## Comparison with Single-Line Strings

| Feature | Single-line `"..."` | Multiline `"""..."""` |
|---------|---------------------|----------------------|
| Escape sequences | All processed (`\n`, `\t`, `\"`, `\\`, `\u{...}`) | Only `\u{...}` processed |
| Line continuation | N/A | Not supported (backslash is literal) |
| Indentation stripping | No | Yes (based on closing delimiter) |
| Can span lines | No (must escape with `\n`) | Yes |
| Raw strings | No | Yes (except Unicode escapes) |

## Examples

### Example 1: Simple Message

```jo
val greeting = """
  Hello, World!
  Welcome to Jo.
  """
```

Output:
```
Hello, World!
Welcome to Jo.

```

### Example 2: Code Block

```jo
val code = """
  def factorial(n: Int): Int =
    if n <= 1 then 1
    else n * factorial(n - 1)
  """
```

Output:
```
def factorial(n: Int): Int =
  if n <= 1 then 1
  else n * factorial(n - 1)

```

### Example 3: File Path (Windows)

```jo
val path = """
  C:\Users\Name\Documents\file.txt
  """
```

Output (backslashes are literal):
```
C:\Users\Name\Documents\file.txt

```

### Example 4: Mixed Content

```jo
val mixed = """
  Text with \n literal backslash-n
  Unicode emoji: \u{1F600}
  Backslash at end\
  """
```

Output:
```
Text with \n literal backslash-n
Unicode emoji: 😀
Backslash at end\

```

### Example 5: Nested Triple Quotes

```jo
val nested = """"
  Code: """
  multi
  line
  """
  """"
```

Output:
```
Code: """
multi
line
"""

```
