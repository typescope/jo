# Block Comments

Block comments in Jo provide a way to comment out multiple lines of code or add multi-line documentation. Unlike traditional `/* */` style comments, Jo uses an innovative delimiter system that makes comments easy to enable and disable.

## Basic Usage

Block comments are delimited by `//[` and `//]`:

```jo
//[
  | This is a block comment
  | that spans multiple lines
//]

def foo = 42
```

## Syntax

### Opening Delimiter

The opening delimiter consists of:
- Two or more forward slashes `//`, `///`, `////`, etc.
- Immediately followed by an opening bracket `[`

Examples: `//[`, `///[`, `////[`

### Closing Delimiter

The closing delimiter consists of:
- The **same number** of forward slashes as the opening delimiter
- Immediately followed by a closing bracket `]`

Examples: `//]`, `///]`, `////]`

**Important**: The number of slashes must match exactly between opening and closing delimiters.

## Examples

### Single-Line Block Comment

```jo
//[ This is a comment //]
def foo = 42
```

### Multi-Line Block Comment

```jo
//[
  | This comment
  | spans multiple
  | lines
//]
def bar = 100
```

### Inline Block Comment

```jo
def baz = //[ inline comment //] 42 + 100
```

### Empty Block Comment

```jo
//[//]
```

## Nesting

Block comments support nesting by using different numbers of slashes:

```jo
///[
Outer comment
  //[ Inner comment //]
  Still in outer comment
///]
```

The inner `//[` ... `//]` is treated as part of the outer comment and doesn't close it. Only `///]` with matching slash count closes the outer comment.

## Enable/Disable Feature

A key feature of Jo's block comment design is the ability to easily enable or disable a block comment by adding or removing a single space.

To disable a block comment and enable the code within:

**Before (code is commented out):**
```jo
//[ This code is commented out
println "debug message 1"
println "debug message 2"
//]
```

**After (code is enabled):**
```jo
// [ This code now runs
println "message 1"
println "message 2"
//]
```

By adding a space after `//`, the opening `//[` becomes a regular single-line comment, and the code inside runs normally.
The closing `//]` also becomes a harmless single-line comment.

## Extended Delimiters

You can use more than two slashes to nest comments or prevent conflicts:

```jo
////[ Outer comment with 4 slashes
  //[ Inner with 2 slashes //]
  ///[ Another inner with 3 slashes ///]
  All still within outer comment
////]
```

This is useful when:
1. You want to comment out code that already contains block comments
2. You need multiple levels of nesting
3. You want to make the comment structure more explicit

## Rules

1. **Exact matching**: Opening and closing delimiters must have the same number of slashes
2. **Minimum slashes**: At least two slashes are required (single `/[` is not a valid delimiter)
3. **No space**: There must be no space between the slashes and the bracket

Unlike multiline strings, block comments do not enforce line restrictions - it's the user's responsibility to structure them correctly.

## Error Handling

If a block comment is not closed before end-of-file, a compile error is reported:

```jo
//[ This comment is never closed
def foo = 42
```

**Error:**
```
Unclosed multiline comment (expected 2 slashes followed by ] to close)
```

## Formal Syntax

### Grammar

```
block-comment ::= opening-delimiter content* closing-delimiter
opening-delimiter ::= '/' '/' '/'* '['
closing-delimiter ::= '/' '/' '/'* ']'   (same slash count as opening)
content ::= <any character except a matching closing delimiter>
```

### Matching Rule

Given an opening delimiter with `n` slashes (where `n ≥ 2`), the comment closes only when encountering `n` consecutive slashes followed by `]`.

For example:

- `//[` (2 slashes) matches only `//]` (2 slashes)
- `///[` (3 slashes) matches only `///]` (3 slashes)
- `////[` (4 slashes) matches only `////]` (4 slashes)
