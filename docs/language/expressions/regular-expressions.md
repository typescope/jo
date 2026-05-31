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
- word-boundary assertions: `\b`, `\B`

Jo-defined shorthands (ASCII only):

| Escape | Meaning |
|--------|---------|
| `\d`   | `[0-9]` |
| `\w`   | `[A-Za-z0-9_]` |
| `\s`   | ASCII whitespace: space, `\t`, `\n`, `\r`, `\f`, vertical tab |
| `\D`   | `[^0-9]` |
| `\W`   | `[^A-Za-z0-9_]` |
| `\S`   | any non-whitespace character (ASCII whitespace complement) |
| `\b`   | word boundary — transition between `\w` and `\W` |
| `\B`   | non-word boundary — between two `\w` or two `\W` characters |

`\d`, `\w`, `\s` and their upper-case negations are expanded by Jo before backend compilation, so they behave identically across all backends. `\b` and `\B` are handled by each backend but have consistent ASCII semantics.

**Portability note:** `\b`, `\B`, `\D`, `\W`, `\S` are portable for ASCII input only. Behavior on Unicode text (for example, whether accented letters count as word characters) is not guaranteed to be consistent across backends.

### Restriction: no shorthands inside `[...]`

`\b`, `\B`, `\D`, `\W`, `\S` are **not allowed inside a character class** and produce a compile-time error:

```jo
`[\b]`   // error: \b and \B are not supported inside a character class
`[\D]`   // error: \D, \W, \S are not supported inside a character class
```

Use the shorthand outside the character class instead:

```jo
`\D`   // instead of [\D]
`\W`   // instead of [\W]
`\S`   // instead of [\S]
```

Unsupported:

- back references
- Unicode classes such as `\p{...}`
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

`matchFirst` returns `Match | None`. Both indexed and named group access are supported:

```jo
match "abc-42".matchFirst(`(?<word>\w+)-(?<num>\d+)`)
  case m: Match =>
    println m[0]        // "abc-42"  (whole match, group 0)
    println m[1]        // "abc"     (group 1 by index)
    println m["word"]   // "abc"     (group 1 by name)
    println m["num"]    // "42"      (group 2 by name; always String)
  case None =>
    println "no match"
```

### Whole-word matching with `\b`

```jo
val word = `\bfoo\b`
println word.matchFirst("foo")     is _: Match   // true  — exact word
println word.matchFirst("foobar")  is None       // true  — not a whole word
println word.matchFirst("a foo b") is _: Match   // true  — surrounded by non-word chars
```

### Find all matches

```jo
val ms = "ab12cd34".matchAll(`\d+`)   // List[Match]
println ms[0].text      // "12"   (matched text)
println ms[0].from      // 2      (start offset, in code points)
println ms[0].length    // 2      (match length, in code points)
println ms[1].text      // "34"
```

Extract all words using `\b`:

```jo
val words = `\b\w+\b`.matchAll("one two three").map(_.text)
// ["one", "two", "three"]
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

- `\d`, `\w`, `\s` and their upper-case negations are expanded by Jo before backend compilation, ensuring identical behavior on all backends.
- `\b` and `\B` are passed to the backend as-is; all backends treat them as ASCII word boundaries.
- Positions (`from`, `length`) are in code points.
- Zero-width behavior for `matchAll`, `replaceAll`, and `splitBy` is defined by Jo and does not depend on host-engine defaults.
