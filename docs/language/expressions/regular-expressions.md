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

## Supported Subset

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
| `\S`   | any character not matched by `\s` |

`\d`, `\w`, `\s`, `\D`, `\W`, `\S` are expanded by Jo before backend compilation, guaranteeing identical ASCII semantics on all backends.

`\b` and `\B` are passed through to the backend as-is. They have consistent ASCII word-boundary semantics on all current backends, but behavior on Unicode text is not guaranteed.

### Restriction: no shorthands inside `[...]`

`\b`, `\B`, `\D`, `\W`, `\S` are **not allowed inside a character class** and produce a compile-time error:

```jo
`[\b]`   // error: \b and \B are not supported inside a character class
`[\D]`   // error: \D, \W, \S are not supported inside a character class
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

## Portability

- `\d`, `\w`, `\s` and their negations are expanded by Jo before backend compilation — identical behavior on all backends.
- `\b` and `\B` are passed to the backend as-is; all current backends treat them as ASCII word boundaries.
- Positions (`from`, `length`) are in code points.
- Zero-width behavior for `matchAll`, `replaceAll`, and `splitBy` is defined by Jo and does not depend on host-engine defaults.

## See Also

- [Regex Patterns](../patterns/regex-patterns.md) — Using regex in `match` and `is`
