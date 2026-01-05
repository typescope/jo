# Terms

A term can be either a modified expression or an if-expression:

```
expr ::= expr_modified | if_expr
expr_modified ::= word {word} {modifier_clause}
if_expr ::= "if" expr "then" expr "else" expr
modifier_clause ::= as_clause | with_clause | allow_clause
```

## Basic Terms

In word-based syntax, function application is word sequencing:

```jo
add 1 2
println "hello"
list.map(x => x + 1)
List.filter isPositive numbers
```

## Term Modifiers

Terms may be modified by:

- **Type ascription**: `term "as" type`
- **With clause**: `term "with" binding {"," binding}`
- **Allow clause**: `term "allow" (qualid {"," qualid} | "none")`

Modified terms are expressions (produce values).

### Type Ascription

Explicitly specify a term's type:

```jo
value as Int
result as Option[String]
data as List[Int]

// Useful for disambiguation
42 as Float
"hello" as Any
```

### With Clause

Override context parameters:

```jo
log("message") with IO.stdout = customOutput

process(data) with
  logger = fileLogger,
  validator = strictValidator

connect(host) with
  timeout = 60,
  retries = 3
```

### Allow Clause

Specify allowed capabilities for security:

```jo
// Allow specific capabilities
process() allow IO, network

// Allow multiple capabilities
sync() allow fileSystem, database, network

// Disallow all capabilities
compute() allow none
```

!!!info
    When discussing syntactic levels, we use "expression" to mean terms. The grammar in [syntax-summary.md](../syntax-summary.md) uses `expr` for this syntactic category.

## If Expressions

Conditional expressions with then/else branches:

```jo
if x > 0 then "positive" else "non-positive"

if condition then
  result1
else
  result2

// Nested if expressions
if x > 0 then
  "positive"
else if x < 0 then
  "negative"
else
  "zero"
```

## Block Terms vs Standalone Terms

Jo distinguishes between two contexts for terms:

### 1. Block Terms

Terms that appear as phrases inside a block. These follow indentation and continuation rules described below.

```jo
val x =
  add 1 2  // Block term

def process() =
  println "hello"  // Block term
  println "world"  // Block term
```

### 2. Standalone Terms

Terms in special contexts that do not follow continuation rules. These stretch as far as possible without regard to indentation. Examples include:

- The condition in `if` expressions: `if <standalone-term> then`
- The scrutinee in `match` expressions: `match <standalone-term>`
- The term wrapped inside `(...)`

The start and end of standalone terms are delimited by the context, no indentation and continuation rules are needed.

```jo
// Block term: follows indentation rules
val x =
  add 1 2

// Standalone term: stretches as far as possible
if add 1 2 == 3 then
  println "yes"
end

// Standalone term in parentheses
val result = (add 1 2 * 3 + 4)
```

## Multiline Block Terms

Block terms continue across lines in two cases:

### 1. Indented Continuation

When a term is followed by an indented line, the indented portion is parsed as a block. Each phrase in the block becomes a single word in the term.

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

### 2. Pipe Continuation

A line beginning with "|" continues the previous term. The "|" character must vertically align with the indentation of the line being continued, and is removed during parsing. What follows the "|" becomes part of the term sequence. A blank line breaks the continuation.

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

Both continuation styles can be mixed:

```jo
process
| step1
    arg1
    arg2
| step2
    arg3
| step3
```

## Examples

```jo
// Simple term
add 1 2

// Term with type ascription
result as Option[String]

// Term with context override
log("message") with logger = fileLogger

// Term with capability restriction
readFile(path) allow open

// If expression
if x > 0 then "positive" else "negative"

// Multiline with indentation
processData
  fetchFromDatabase
  validateData
  transformData

// Multiline with pipes
data
| filter(x => x.isValid)
| map(x => x.transform)
| collect

// Complex term with modifiers
fetchData(url)
  with timeout = 30
  allow network
  as Result[Data, Error]
```

## See Also

- [Words](words.md) - Components of terms
- [Phrases](phrases.md) - Terms in block context
- [Blocks](blocks.md) - Collections of phrases
