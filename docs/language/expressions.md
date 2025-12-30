# Words, Terms, Phrases and Blocks

This document specifies the syntax of Jo's computational constructs. Jo uses a word-based syntax organized into four syntactic levels: words, terms, phrases, and blocks.

## Overview

Jo distinguishes between **syntactic structure** and **semantic categories**:

**Syntactic levels:**

- **word** - atomic syntactic unit
- **term** - sequence of words
- **phrase** - element of a block
- **block** - sequence of phrases

**Semantic categories:**

- **expression** - produces a value
- **statement** - does not produce a value

These are orthogonal: a phrase may be either an expression or a statement.

## Word-Based Syntax

Jo's syntax is word-based: terms are sequences of words. This design enables uniform parenthesis-less syntax across terms, types, and patterns:

```jo
// Terms
add 1 2
List.map f list

// Types
List Int
Option String

// Patterns
Some x
Cons head tail
```

## Words

A word is an atomic syntactic unit.

### Literals

```
integer  ::= ["-"] digit {digit}
boolean  ::= "true" | "false"
character ::= "'" <character> "'"
string   ::= <single-line-string> | <multi-line-string>
```

Examples: `42`, `-17`, `true`, `'a'`, `"hello"`

### Identifiers

```
identifier ::= (letter | "_") {letter | digit | "_"}
operator   ::= opchar {opchar}
qualid     ::= identifier {"." identifier}
```

Examples: `x`, `counter`, `List.map`, `+`, `&&`

### Fence

`"(" phrase ")"`

Parentheses group expressions and override precedence:

```jo
(x + y) * z
(list.filter isPositive)
```

### Record

`"{" [named_arg {"," named_arg}] "}"`

Record literals create labeled data:

```jo
{x = 10, y = 20}
{name = "Alice", age = 30}
{}  // empty record
```

### List

`"[" [term {"," term}] "]"`

List literals:

```jo
[1, 2, 3]
["hello", "world"]
[]  // empty list
```

### Object

`"{" {member} "}"`

Object expressions create anonymous objects with members:

```jo
{
  val count = 0
  def increment() = count = count + 1
  def get() = count
}
```

### Lambda

`(param_section | identifier) "=>" block`

Anonymous functions:

```jo
x => x + 1              // single parameter
(x, y) => x + y        // multiple parameters
() => 42               // zero parameters
(x: Int) => x * 2      // with type annotation
```

**Lambda interfaces:** Lambdas automatically adapt to interface types with a single abstract method:

```jo
interface Predicate[T]
  def test(x: T): Bool
end

val isEven: Predicate[Int] = x => x % 2 == 0
```

Context parameters in lambda interfaces come from the call site:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)
logger.log("test") with IO.stdout = customOutput
```

### Function Application

`word "(" [term {"," term}] ")"`

Call functions with arguments:

```jo
println("hello")
add(1, 2)
list.map(x => x * 2)
```

### Type Application

`word "[" type {"," type} "]"`

Apply type arguments:

```jo
List[Int]
Option[String]
identity[Int](42)
```

### Bracket Application

`word "[" term {"," term} "]"`

Access elements by index or key:

```jo
array[0]
map["key"]
matrix[i, j]
```

### Selection

`word "." identifier`

Access object members:

```jo
point.x
list.length
Math.sqrt
```

### View Access

`word "." "view" "[" type "]"`

Explicit view conversion:

```jo
range.view[Iterator[Int]]
value.view[Comparable[T]]
```

### New Expression

`"new" qualid [type_args] [args]`

Create class instances:

```jo
new Point(10, 20)
new Array[Int](100)
new HashMap[String, Int]
```

### Begin Block

`"begin" block "end"`

Explicit block expression (requires `end`):

```jo
begin
  val x = 10
  val y = 20
  x + y
end
```

### Is Expression

`word "is" simple_pattern`

Pattern matching that returns boolean:

```jo
value is Some(x)
list is Cons(head, tail)
```

Matched variables are bound in the success branch of conditionals:

```jo
if value is Some(x) then
  println(x)  // x is bound here
```

## Terms

A term can be either a modified expression or an if-expression:

```
expr ::= expr_modified | if_expr
expr_modified ::= word {word} {modifier_clause}
if_expr ::= "if" expr "then" expr "else" expr
modifier_clause ::= as_clause | with_clause | allow_clause
```

### Term Modifiers

Terms may be modified by:

- **Type ascription**: `term "as" type`
- **With clause**: `term "with" binding {"," binding}`
- **Allow clause**: `term "allow" (qualid {"," qualid} | "none")`

Modified terms are expressions (produce values).

!!! info
    When discussing syntactic levels, we use "expression" to mean terms. The grammar in [syntax-summary.md](syntax-summary.md) uses `expr` for this syntactic category.

In word-based syntax, function application is word sequencing:

```jo
add 1 2
println "hello"
list.map(x => x + 1)
```

### Block Terms vs Standalone Terms

Jo distinguishes between two contexts for terms:

1. **Block terms** - terms that appear as phrases inside a block. These follow indentation and continuation rules described below.

2. **Standalone terms** - terms in special contexts that do not follow continuation rules. These stretch as far as possible without regard to indentation. Examples include:

     - The condition in `if` expressions: `if <standalone-term> then`
     - The scrutinee in `match` expressions: `match <standalone-term>`
     - The term wrapped inside `(...)`

The start and end of standalone terms are delimited by the context, no
indentation and continuation rules are needed.

```jo
// Block term: follows indentation rules
val x =
  add 1 2

// Standalone term: stretches as far as possible
if add 1 2 == 3 then
  println "yes"
```

### Multiline Block Terms

Block terms continue across lines in two cases:

1. **Indented continuation**: When a term is followed by an indented line, the indented portion is parsed as a block. Each phrase in the block becomes a single word in the term.

2. **Pipe continuation**: A line beginning with "|" continues the previous term. The "|" character must vertically align with the indentation of the line being continued, and is removed during parsing. What follows the "|" becomes part of the term sequence. A blank line breaks the continuation.

```jo
// Indented continuation
gcd
  10
  15

// Pipe continuation - "|" aligns with "result"
result
| filter isPositive
| map double
| sum
```

## Phrases

A phrase is a syntactic element that may appear in a block:

```
phrase ::= expr_modified | assignment | definition | control_flow
```

### Assignment

```
assignment ::= lhs "=" block
lhs ::= identifier | selection | bracket_application
```

Assignments are statements.

### Definitions

```
definition ::= val_def | var_def | fun_def | pattern_def | type_def
```

Definitions are statements. See [definitions.md](definitions.md).

### Control Flow

#### If

```
if ::= "if" term "then" block ["else" block] ["end"]
```

If constructs are expressions.

#### Match

```
match ::= "match" term {case} ["end"]
case ::= "case" pattern "=>" block
```

Match constructs are expressions. See [pattern-language.md](pattern-language.md) for pattern syntax.

#### While

```
while ::= "while" term "do" block ["end"]
```

While constructs are statements.

#### For

```
for ::= "for" expr_pattern "in" term ["if" term] "do" block ["end"]
```

For loops iterate over collections by pattern matching on each element.

The syntax desugars to:

```jo
val $iter = expr.iterator
while $iter.hasNext do
  case expr_pattern = $iter.next
  if cond then
    block
```

For loops are statements.

**Exhaustive Patterns:** The pattern in a for loop must be exhaustive. The compiler warns about non-exhaustive patterns that could fail at runtime:

```jo
// Warning: non-exhaustive pattern (missing None)
for Some(x) in optionList do
  println x

// OK: exhaustive pattern - all lists have elements
for x in list do
  println x

// OK: filtering via guard condition
for Point(x, y) in points if x > 0 do
  println x
```

**Filtering:** Use the optional `if` clause to filter elements. You can also use `is` expressions in the condition:

```jo
// Filter using is expression
for elem in mixedList if elem is Some(x) do
  println x

// Combine pattern matching and filtering
for Point(x, y) in points if x > 0 && y > 0 do
  println (x + y)
```

!!! note
    Pattern match failures in for loops cause runtime errors (like case definitions). Use `is` expressions in the `if` clause for filtering instead of relying on non-exhaustive patterns.

## Blocks

A block is a sequence of phrases:

```
block ::= {phrase}
```

### Block-Starting Constructs

The following constructs automatically start blocks after their respective keywords:

**Control flow:**

- `if` ... `then` - starts a block for the then-branch
- `if` ... `else` - starts a block for the else-branch
- `while` ... `do` - starts a block for the loop body
- `for` ... `do` - starts a block for the loop body
- `case` ... `=>` - starts a block for the case body
- `begin` - starts an explicit block, requires matching `end`

!!! warning
    Unlike other constructs where `end` is optional, `begin` requires a matching `end` marker. This is intentional: the explicit use of `begin` signals the programmer's intent to explicitly mark a region of code, and a missing `end` would be inconsistent with that intent.

**Definitions:**

- `val` ... `=` - starts a block for the value definition
- `var` ... `=` - starts a block for the variable definition
- `def` ... `=` - starts a block for the function body
- `param` ... `=` - starts a block for the parameter default value

**Assignments and lambdas:**

- `=` - starts a block for assignment values
- `=>` - starts a block for lambda bodies

### Block Delimiters

Blocks are delimited by the line indentation of delimiters:

- For control flow and definitions, the block delimiter is the first keyword (`if`, `while`, `begin`, `val`, `var`, `def`, `param`)
- For assignments and lambdas, the block delimiter is the operator (`=`, `=>`)

The usual [offside rule](https://en.wikipedia.org/wiki/Off-side_rule) applies: a block continues while phrases remain at greater indentation than the delimiter, and ends when indentation returns to or before the delimiter's level.

All phrases in a block must be vertically aligned.

!!! info
    Pattern definitions (`pattern` ... `=`) do not start blocks. Their right-hand side consists of case patterns, which are not expressions and therefore not organized into blocks.

### Block Values

A block is always an expression. Its value is determined by its final phrase:

- If the final phrase is an expression, the block evaluates to that value
- If the final phrase is a statement, a unit value is synthesized
