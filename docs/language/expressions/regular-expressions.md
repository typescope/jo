# Regular Expressions

Jo supports regular expression literals using backtick syntax.

## Literal Syntax

Regex literals are written between backticks:

```jo
`\d+`
`(?im)^foo$`
```

- `` `pattern` ``: no flags
- `` `(?flags)pattern` ``: optional inline flag prefix `(?ims)` at the start

Inside a regex literal:
- backslash sequences are passed through verbatim to the regex engine (no string-style escaping)
- a literal backtick must be written as `` \` ``
- literals must fit on a single line

## Flags

Jo supports three flags, written as an inline prefix `(?flags)`:

- `i` (case-insensitive): letter matching ignores case
- `m` (multiline anchors): `^` and `$` match line boundaries, not only whole-string boundaries
- `s` (dotall): `.` also matches newline

Examples:

```jo
println "ABC".exists(`(?i)abc`)              // true  (case-insensitive: A matches a)
println "x\nfoo\ny".matchFirst(`(?m)^foo$`)  // foo  (^ matches start of line, not string)
println "a\nc".exists(`(?s)^a.c$`)           // true  (. matches the \n newline)
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
println "123".exists(`^\d+$`)   // true
println "a1".exists(`^\d+$`)    // false

// Partial match
println "abc123".exists(`\d+`)   // true
println "hello".exists(`\d+`)    // false
```

### Find the first match

Returns `Match | None`. Both indexed and named access are supported:

```jo
if "abc-42".matchFirst(`(?<word>\w+)-(?<num>\d+)`) is Some(m) then
  println m[0]        // "abc-42"  (whole match, group 0)
  println m[1]        // "abc"     (group 1 by index)
  println m["word"]   // "abc"     (group 1 by name)
  println m["num"]    // "42"      (group 2 by name; always String)
```

### Find all matches

```jo
val ms = "ab12cd34".matchAll(`\d+`)   // List[Match]
println ms[0].text      // "12"   (matched text)
println ms[0].from      // 2      (start offset, in code points)
println ms[0].length    // 2      (match length, in code points)
println ms[1].text      // "34"
```

### Replace text

```jo
// callback receives each Match and returns the replacement String
println "a1b22c333".replaceAll(`\d+`, _ => "N")                               // "aNbNcN"
println "hello world".replaceFirst(`(\w+)\s+(\w+)`, m => m[2] + " " + m[1])  // "world hello"
```

### Split by regex

```jo
println "a:b:c".splitBy(`:`)        // [a, b, c]
println "a  b   c".splitBy(`\s+`)   // [a, b, c]  (runs of whitespace treated as one delimiter)
```

### Build regexes dynamically

```jo
val source = "^[A-Za-z_][A-Za-z0-9_]*$"
match Regex.compile(source)      // validate before compiling
  case r: Regex =>
    println "name_42".exists(r)     // true
  case err: String =>
    println err                     // human-readable error message
```

Dynamic regexes may also use the inline flag prefix:

```jo
val r = Regex.compile("(?im)^foo$") rescue err: String => abort err
```

If you are inserting literal user text into a dynamic pattern, escape it:

```jo
val key = "a+b"
val r = Regex.compile("^" + Regex.escape(key) + "$") rescue err: String => abort err
```

## Notes on Portability

- Jo normalizes `\d`, `\w`, `\s` before backend compilation.
- Positions (`from`, `length`) are defined in code points.
- Zero-width behavior for `matchAll`, `replaceAll`, and `splitBy` is defined by Jo and does not depend on host defaults.
