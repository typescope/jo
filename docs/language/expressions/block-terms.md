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

## Colon Calls As Block Terms

Colon calls are one kind of block term:

```jo
def example =
  println: "hello"
  send:
    to = "team@example.com"
    subject = "Status"
```

Their call syntax is documented in [Applications](applications.md). In this section, the important point is that colon calls live at phrase level inside blocks.

## Block Term Boundaries

Understanding when a block term ends is important:

```jo
def example =
  // This is one block term
  process:
    data
    filter
    map

  // This is a separate block term (separated by blank line)
  save: result

  // This is another block term
  cleanup
```

## Comparison with Isolated Terms

Unlike [isolated terms](isolated-terms.md), block terms:

- Follow indentation rules
- End at dedentation or blank lines
- Appear as phrases within blocks
- Can use colon-call blocks

```jo
// Block term: phrase-level call
val x =
  add: 1, 2  // Ends here

// Isolated term: stretches as far as possible
if add 1 2 == 3 then  // Continues until "then"
  println "yes"
end
```

## See Also

- [Applications](applications.md) - Call syntax, including colon calls
- [Isolated Terms](isolated-terms.md) - Terms in delimited contexts
- [Phrases](phrases.md) - Block terms and other phrases
- [Blocks](blocks.md) - Collections of phrases
