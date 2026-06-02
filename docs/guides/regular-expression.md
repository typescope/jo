# Regular Expressions

Regex in most languages lives in an awkward middle ground: the pattern is expressive,
but wiring it into typed control flow means integer-indexed group access, nullable
match objects, and boilerplate to check whether a group participated.

Jo treats regex as a first-class pattern: named groups bind directly as
flow-typed variables in `match` and `if`/`is` expressions.

## Testing for a Match

Use `exists` to check whether a pattern occurs anywhere in the string. Anchor with `^`
and `$` to require a full-string match:

```jo
// Partial match
"abc123".exists(`\d+`)    // true
"hello".exists(`\d+`)     // false

// Full-string match
"123".exists(`^\d+$`)     // true
"a1".exists(`^\d+$`)      // false
```

## Extracting the First Match

`matchFirst` returns `Option[Match]`. Both indexed and named group access are supported:

```jo
match "abc-42".matchFirst(`(?<word>\w+)-(?<num>\d+)`)
case Some(m) =>
    println m[0]        // "abc-42"  (whole match)
    println m[1]        // "abc"     (group 1 by index)
    println m["word"]   // "abc"     (group 1 by name)
    println m["num"]    // "42"      (group 2 by name)
case None =>
    println "no match"
```

## Finding All Matches

`matchAll` returns a `List[Match]`:

```jo
val ms = "ab12cd34".matchAll(`\d+`)
println ms[0].text    // "12"
println ms[0].from    // 2     (start offset, code points)
println ms[0].length  // 2     (length, code points)
println ms[1].text    // "34"

// Extract all words
val words = `\b\w+\b`.matchAll("one two three").map(_.text)
// ["one", "two", "three"]
```

## Replacing Text

```jo
// Replace all: callback receives each Match, returns the replacement
"a1b22c333".replaceAll(`\d+`, _ => "N")                              // "aNbNcN"

// Replace first: swap two words
"hello world".replaceFirst(`(\w+)\s+(\w+)`, m => m[2] + " " + m[1]) // "world hello"
```

## Splitting

```jo
"a:b:c".splitBy(`:`)       // ["a", "b", "c"]
"a  b   c".splitBy(`\s+`)  // ["a", "b", "c"]  (runs of whitespace as one delimiter)
```

## Whole-Word Matching

Use `\b` word-boundary assertions to match complete words:

```jo
val word = `\bfoo\b`
word.matchFirst("foo")     is Some(_)  // true  — exact word
word.matchFirst("foobar")  is None     // true  — not a whole word
word.matchFirst("a foo b") is Some(_)  // true  — surrounded by non-word chars
```

## Using Regex in Patterns

Regex literals work directly in `if`/`is` and `match` expressions, binding named
groups as local variables:

```jo
// Parse a date — named groups y, m, d are bound on match
if date is `^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$` then
  new Date(y.toInt, m.toInt, d.toInt)
else
  abort "Invalid date"

// match with a bound Match result
match date
case m @ `^(\d{4})-(\d{2})-(\d{2})$` =>
  new Date(m[1].toInt, m[2].toInt, m[3].toInt)
case _ =>
  abort "Invalid date"
```

Extract an LLM-generated code block (dotall flag so `.` matches newlines):

```jo
if message is `(?s)<code>(?<prog>.*)</code>` then
  println prog
```

Handle an optional named group:

```jo
if input is m @ `^(?<name>\w+)(?:-(?<tag>\w+))?$` then
  if m.isGroupMatched("tag") then println tag
  else println "no tag"
```

## Building Regexes Dynamically

When the pattern is not known at compile time, use `Regex.compile`:

```jo
match Regex.compile(source)
case Ok(r) =>
    "name_42".exists(r)      // true if source matches
case Err(err) =>
    println err              // human-readable error message
```

Always escape user-supplied text before embedding it in a dynamic pattern:

```jo
val key = "a+b"
val r = Regex.compile("^" + Regex.escape(key) + "$") rescue Err(m) => abort m
```

## See Also

- [Regular Expressions](../language/expressions/regular-expressions.md) — Literal syntax, flags, supported subset
- [Regex Patterns](../language/patterns/regex-patterns.md) — Spec for regex in `match`/`is`
