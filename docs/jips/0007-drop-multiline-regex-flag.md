---
author: Fengyun Liu
status: Accepted
created: 2026-10-15
title: Drop the multiline regex flag
---

# JIP 0007 — Drop the multiline regex flag

<JipMeta />

## Summary

Remove the `m` flag from Jo regular expressions. `^` matches only the start of
the input, and `$` matches only its end, including when the input ends in a
newline. The `i` and `s` flags remain available. Line-oriented matching uses
`String.lineIterator`, which recognizes both `\n` and `\r\n` separators.

## Motivation

The name *multiline* suggests that the flag enables matching across lines. It
does not: `m` changes only where `^` and `$` match. A regex can already match
text containing newlines without `m`. The `s` flag lets `.` match newlines.

The host regex engines also disagree about anchors near `\r\n`. For example,
with multiline anchors, `a$` matches the `a` in `"a\r\nb"` in JavaScript and
Java, but not in Python or Ruby. The same Jo source can therefore yield
different matches depending on the backend.

This proposal removes the flag for three reasons:

1. **Semantic clarity.** The meaning of `m` is easy to mistake, and its line
   boundary behavior is difficult to explain consistently for CRLF input.
2. **Marginal utility.** Common line-oriented tasks can use `lineIterator`,
   which gives each line to the regex without its separator.
3. **Portability.** Making multiline anchors agree across the host engines
   would require Jo to implement or rewrite their boundary behavior.

## Justification

### Semantic clarity

Without `m`, an anchor has one meaning throughout a program: `^` asserts the
start of the entire input, and `$` asserts its end. A final newline is part of
the input, so `$` matches after it. Neither anchor consumes a character.

With `m`, even the meaning of a line boundary needs qualification for CRLF
input. Is `\r\n` one line ending or two characters that each create an anchor
position? Should `$` match before `\r` or before `\n`? Those choices are visible
in matches and hard to infer from the name *multiline*.

The word *multiline* conflates two different questions: whether a pattern may
span lines and whether anchors may assert positions within lines. Removing `m`
keeps the first question independent of anchors. Explicit newlines can occur in
a pattern, and `(?s)` allows `.` to cross them.

### Marginal utility

A common use of `(?m)^foo$` is to find lines equal to `foo` in a file, log, or
configuration value. Jo can express that directly:

```jo
for line in input.lineIterator do
  if line is `^foo$` then
    println line
  end
end
```

The same approach supports line validation, extraction, and filtering. It
makes the unit of work explicit and handles LF and CRLF separators in the same
way. It also lets each match use ordinary whole-input anchors against a line.

Patterns spanning lines can match separators explicitly, without any flags:

```jo
val lf = "a\nb"
val crlf = "a\r\nb"

println: lf is `a\nb`         // true
println: crlf is `a\r\nb`     // true

// Accept either LF or CRLF.
println: lf is `a\r?\nb`      // true
println: crlf is `a\r?\nb`    // true
```

This is not an exact substitute for every use of `m`. `lineIterator` omits line
separators and does not preserve positions in the original input. A program
that needs original match offsets, line endings, or whole-input replacement
must track those details explicitly. Those uses benefit from multiline anchors,
but are too narrow to justify retaining backend-specific semantics for `m`.

### Portability

Regex engines disagree on which positions count as line boundaries, especially
around CRLF. In `"a\r\nb"`, some engines let `$` assert immediately after `a`,
before `\r`. Others assert it only before `\n`, after the `\r`. Similar choices
affect where `^` can assert. Passing `m` through to each backend would leave
observable differences in both matches and match positions.

Jo could define its own line-boundary rule and translate every multiline
anchor for every backend. That requires engine-specific rewrites, including
care around escaped anchors, character classes, groups, and zero-width matches.
It would add implementation complexity and potentially work to regex matching
for a feature whose main use is already covered by line iteration. Removing
`m` keeps Jo's anchor contract small and portable.

## Consequences

This is a source-incompatible change. A regex literal with `(?m)` is rejected,
and dynamically compiled patterns with `m` fail validation. Programs matching
individual lines should iterate over `String.lineIterator` and match each line.
Programs relying on positions or separators must handle them explicitly.

The `s` flag remains available for patterns that use `.` across newlines.
Removing `m` does not prevent a regex from matching text containing multiple
lines.
