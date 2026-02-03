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

Block terms can continue across multiple lines using two mechanisms: indented continuation and pipe continuation.

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

### Pipe Continuation

A line beginning with `|` continues the previous block term. The pipe character must vertically align with the indentation of the line being continued.

**Rules:**

- The `|` is removed during parsing
- What follows the `|` becomes part of the term sequence
- The `|` must align with the indentation of the term being continued
- A blank line breaks the continuation

```jo
// Pipe continuation - "|" aligns with "result"
result
| filter isPositive
| map double
| sum

// Equivalent to:
result filter isPositive map double sum

// Another example
numbers
| filter(x => x > 0)
| map(x => x * 2)
| fold(0, (acc, x) => acc + x)
```

### Combining Indentation and Pipes

Both continuation styles can be mixed in the same block term:

```jo
process
| step1
    arg1
    arg2
| step2
    arg3
| step3

// Equivalent to:
process step1 arg1 arg2 step2 arg3 step3
```

In this example:
- `|` creates continuation lines
- Indented blocks under each step provide arguments

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

### Multiline with Pipes

```jo
data
| filter(x => x.isValid)
| map(x => x.transform)
| collect
```

### Complex Combination

```jo
pipeline
| loadData
    "input.csv"
    "utf-8"
| transform
    removeNulls
    normalizeValues
| save
    "output.csv"
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
- Can use continuation mechanisms (indentation and pipes)

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
