# String Literals

This guide shows how to build, format, and work with strings in Jo. For the formal
syntax and processing rules, see [String Literals](../language/expressions/string.md).

## Interpolation

Embed values directly in a string with `\{...}`. Any expression that converts to
`String` works — primitive types and anything with a `.toString` method:

```jo
val name = "Alice"
val score = 98
println "Player \{name} scored \{score} points!"
// Player Alice scored 98 points!

val x = 3
val y = 4
println "Hypotenuse: \{(x*x + y*y).sqrt}"
```

To include a literal `\{`, escape it with `\\{`:

```jo
println "Template syntax: \\{variable}"
// Template syntax: \{variable}
```

## Custom Types

Define a `toString` method to make any type interpolatable:

```jo
class Point(x: Int, y: Int)
  def toString: String = "(\{x}, \{y})"
end

val p = Point(3, 4)
println "Position: \{p}"
// Position: (3, 4)
```

## Multiline Strings

Use triple quotes for text that spans multiple lines. The indentation of the closing
`"""` sets the baseline — that many leading spaces are stripped from every line:

```jo
val greeting = """
  Hello, World!
  Welcome to Jo.
  """
// "Hello, World!\nWelcome to Jo.\n"
```

Combine with interpolation for templates:

```jo
val name = "Alice"
val age  = 25
val profile = """
  Name:   \{name}
  Age:    \{age}
  Status: active
  """
println profile
// Name:   Alice
// Age:    25
// Status: active
//
```

Control indentation by placing the closing delimiter:

```jo
// Less base indentation stripped (closing `"""` at column 0)
val code = """
def factorial(n: Int): Int =
  if n <= 1 then 1
  else n * factorial(n - 1)
"""
```

## Raw Strings

Multiline strings are raw: backslash sequences other than `\u{...}` are preserved
literally. This makes them ideal for Windows paths, regex patterns, and any content
that contains backslashes:

```jo
val path = """
  C:\Users\Alice\Documents\report.txt
  """

val pattern = """
  ^\d{4}-\d{2}-\d{2}$
  """
```

## Embedding Triple Quotes

To include `"""` in a multiline string, use four (or more) opening and closing quotes:

```jo
val doc = """"
  Example:
    val x = """hello"""
  """"
// "Example:\n  val x = \"\"\"hello\"\"\"\n"
```

## HTML / SQL Templates

Multiline interpolation is clean for structured text:

```jo
val title   = "Report"
val content = "Q1 results..."

val html = """
  <html>
    <head><title>\{title}</title></head>
    <body>\{content}</body>
  </html>
  """

val userId = 42
val query = """
  SELECT *
  FROM orders
  WHERE user_id = \{userId}
    AND status = 'active'
  """
```

## See Also

- [String Literals](../language/expressions/string.md) — Syntax, escape sequences, processing rules
- [Regular Expression Guide](regular-expression.md) — Regex with strings
