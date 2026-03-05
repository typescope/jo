# Regular Expressions

Jo provides portable regular expressions through `jo.regex.Regex`.

## Literal Syntax

Regex literals use tagged literal syntax:

```jo
#r"\d+"
#r[im]"^foo$"
```

- `#r"pattern"`: no flags
- `#r[flags]"pattern"`: flags are one or more of `i`, `m`, `s` (no duplicates)

Regex literals are raw regex payloads:

- interpolation (`\{...}`) is not supported
- a literal `"` must be written as `\"`

## Flags

Jo supports three flags:

- `i` (case-insensitive): letter matching ignores case
- `m` (multiline anchors): `^` and `$` match line boundaries, not only whole-string boundaries
- `s` (dotall): `.` also matches newline

Examples:

```jo
println "ABC".exists(#r[i]"abc")              // true  (case-insensitive: A matches a)
println "x\nfoo\ny".matchFirst(#r[m]"^foo$")  // Some(foo)  (^ matches start of line, not string)
println "a\nc".exists(#r[s]"^a.c$")           // true  (. matches the \n newline)
```

## Supported Regex Subset

Jo intentionally supports a conservative portable subset:

- literals and escaped metacharacters: `\(` `\)` `\[` `\]` `\{` `\}` `\*` `\+` `\?` `\|` `\\`
- wildcard `.`
- character classes: `[abc]`, `[a-z]`, `[^a-z]`
- concatenation and alternation: `ab`, `a|b`
- groups:
    - capturing: `( ... )`
    - named capturing: `(?<name> ... )`
    - non-capturing: `(?: ... )`
- quantifiers:
    - greedy: `*`, `+`, `?`, `{n}`, `{n,m}`, `{n,}`
    - lazy: `*?`, `+?`, `??`, `{n,m}?`, `{n,}?`
- anchors: `^`, `$`
- shorthands: `\d`, `\D`, `\w`, `\W`, `\s`, `\S`

Jo-defined shorthands:

- `\d` = `[0-9]`
- `\w` = `[A-Za-z0-9_]`
- `\s` = ASCII whitespace (` `, `\t`, `\n`, `\r`, `\f`, vertical tab)

Unsupported in current version:

- back references
- Unicode classes such as `\p{...}`
- word-boundary escapes `\b`, `\B`
- lookaround
- atomic groups
- possessive quantifiers
- inline mode modifiers

## Group Semantics

- group `0` is the whole match
- groups `1..n` are capturing groups in left-to-right order by opening `(`
- named groups are also numbered in that same sequence
- named group names must match: `[A-Za-z_][A-Za-z0-9_]*`
- if a group captures multiple times, only the last capture is kept

## Common Tasks

### Check whether a match exists

```jo
// Full-string match (use anchors)
println "123".exists(#r"^\d+$")   // true
println "a1".exists(#r"^\d+$")    // false

// Partial match
println "abc123".exists(#r"\d+")   // true
println "hello".exists(#r"\d+")    // false
```

### Find the first match

Both indexed and named access are supported:

```jo
if "abc-42".matchFirst(#r"(?<word>\w+)-(?<num>\d+)") is Some(m) then
  println m[0]        // "abc-42"  (whole match, group 0)
  println m[1]        // "abc"     (group 1 by index)
  println m["word"]   // "abc"     (group 1 by name)
  println m["num"]    // "42"      (group 2 by name; always String)
```

### Find all matches

```jo
val ms = "ab12cd34".matchAll(#r"\d+")
println ms[0].text      // "12"   (matched text)
println ms[0].from      // 2      (start offset, in code points)
println ms[0].length    // 2      (match length, in code points)
println ms[1].text      // "34"
```

### Replace text

```jo
// callback receives each Match and returns the replacement String
println "a1b22c333".replaceAll(#r"\d+", _ => "N")                               // "aNbNcN"
println "hello world".replaceFirst(#r"(\w+)\s+(\w+)", m => m[2] + " " + m[1])   // "world hello"
```

### Split by regex

```jo
println "a:b:c".splitBy(#r":")        // [a, b, c]
println "a  b   c".splitBy(#r"\s+")   // [a, b, c]  (runs of whitespace treated as one delimiter)
```

### Build regexes dynamically

```jo
val source = "^[A-Za-z_][A-Za-z0-9_]*$"
match Regex.checkError(source)      // validate before compiling
  case None =>
    val r = Regex.compile(source)
    println "name_42".exists(r)     // true
  case Some(err) =>
    println err                     // human-readable error message
```

If you are inserting literal user text into a dynamic pattern, escape it:

```jo
val key = "a+b"
val r = Regex.compile("^" + Regex.escape(key) + "$")
```

## Notes on Portability

- Jo normalizes `\d`, `\w`, `\s` before backend compilation.
- Positions (`from`, `length`) are defined in code points.
- Zero-width behavior for `findAll`, `replaceAll`, and `split` is defined by Jo and does not depend on host defaults.
