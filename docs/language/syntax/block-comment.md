# Block Comments

Block comments are delimited by `//[` and `//]`:

```jo
//[ This is a block comment
    that spans multiple lines
//]

def foo = 42
```

## Syntax

```
block_comment    = opening_delimiter content* closing_delimiter
opening_delimiter = "/" "/" {"/"} "["
closing_delimiter = "/" "/" {"/"} "]"   (same slash count as opening)
content          = <any character except a matching closing delimiter>
```

The opening and closing delimiter must have the same number of slashes (minimum 2).
There must be no space between the slashes and the bracket.

## Nesting

Use a higher slash count to nest comments:

```jo
///[
  Outer comment
  //[ Inner comment //]
  Still in outer comment
///]
```

Only `///]` closes the outer comment; the inner `//[...//]` is treated as content.

## Rules

1. Opening and closing delimiters must have the same slash count.
2. Minimum two slashes (`//[`); a single `/[` is not valid.
3. No space between slashes and bracket.
4. An unclosed block comment is a compile-time error.
