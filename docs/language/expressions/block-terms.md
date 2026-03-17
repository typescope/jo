# Block Terms

Block terms are terms that appear as phrases inside a block. They follow indentation and continuation rules to determine where the term ends.

## Basic Block Terms

A block term appears on its own line within a block:

```jo
def process() =
  println "hello"  // Block term
  println "world"  // Block term

val x =
  add 1 2  // Block term
```

Block terms end when:
- The indentation decreases (dedentation)
- A blank line is encountered
- The end of the block is reached

## Multiline Block Terms

Block terms can continue across multiple lines using indented continuation.

### Indented Continuation

When a block term is followed by an indented line, the indented portion is parsed as a nested block. Each phrase in that nested block becomes a single word in the term.

```jo
gcd
  10
  15

result
  filter isPositive
  map double
  sum

// Equivalent to:
gcd 10 15
result filter isPositive map double sum
```

The indented block provides arguments or continuation of the term.

## Examples

### Simple Block Terms

```jo
def compute =
  val x = add 1 2
  val y = multiply x 3
  subtract y 1
```

### Multiline with Indentation

```jo
processData
  fetchFromDatabase
  validateData
  transformData

// Each indented line becomes a word in the term
```

## Block Term Boundaries

Understanding when a block term ends is important:

```jo
def example =
  // This is one block term
  process data
    filter
    map

  // This is a separate block term (separated by blank line)
  save result

  // This is another block term
  cleanup
```

## Comparison with Isolated Terms

Unlike [isolated terms](isolated-terms.md), block terms:

- Follow indentation rules
- End at dedentation or blank lines
- Appear as phrases within blocks
- Can use indented continuation

```jo
// Block term: respects indentation
val x =
  add 1 2  // Ends here

// Isolated term: stretches as far as possible
if add 1 2 == 3 then  // Continues until "then"
  println "yes"
end
```

## See Also

- [Isolated Terms](isolated-terms.md) - Terms in delimited contexts
- [Phrases](phrases.md) - Block terms and other phrases
- [Blocks](blocks.md) - Collections of phrases
