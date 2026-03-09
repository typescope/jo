# Isolated Terms

Isolated terms (also called standalone terms) are terms that appear in special syntactic contexts where they do not follow indentation and continuation rules. These terms stretch as far as possible without regard to indentation.

Isolated terms appear in the following contexts:

1. **Condition expressions in `if`**: The term between `if` and `then`
2. **Scrutinee in `match`**: The term between `match` and the first `case`
3. **Parenthesized expressions**: The term wrapped inside `(...)`
4. **Fence expressions**: Terms in other delimited contexts

The start and end of isolated terms are delimited by the surrounding context, so no indentation or continuation rules are needed.

## Grammar

A term can be a delimited expression, an if-expression, or a lambda:

```
expr ::= delimited_expr | if_expr | lambda
delimited_expr ::= simple_expr [modifier_clause]
if_expr ::= "if" simple_expr "then" expr "else" expr
simple_expr ::= word {word}
modifier_clause ::= as_clause | with_clause | do_clause
do_clause ::= "do" lambda
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

## Examples

```jo
// Simple term
add 1 2

// Term with type ascription
result as Option[String]

// Term with context override
log("message") with logger = fileLogger

// If expression
if x > 0 then "positive" else "negative"

// Complex term with modifiers
fetchData(url)
  with timeout = 30
  as Result[Data, Error]
```

For examples of multiline terms with indentation and pipe continuation, see [Block Terms](block-terms.md).

## See Also

- [Words](words.md) - Components of terms
- [Block Terms](block-terms.md) - Terms following indentation rules
- [Phrases](phrases.md) - Terms in block context
- [Blocks](blocks.md) - Collections of phrases
