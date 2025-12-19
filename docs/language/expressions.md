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

A word is an atomic syntactic unit. The word forms are:

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

### Structured Forms

- **Fence**: `"(" phrase ")"`
- **Record**: `"{" [named_arg {"," named_arg}] "}"`
- **List**: `"[" [term {"," term}] "]"`
- **Object**: `"{" {member} "}"`
- **Lambda**: `(param_section | identifier) "=>" block`

### Applications

- **Function application**: `word "(" [term {"," term}] ")"`
- **Type application**: `word "[" type {"," type} "]"`
- **Bracket application**: `word "[" term {"," term} "]"`
- **Selection**: `word "." identifier`
- **View access**: `word "." "view" "[" type "]"`

### Other Forms

- **New expression**: `"new" qualid [type_args] [args]`
- **Begin block**: `"begin" block "end"`
- **Is expression**: `word "is" simple_pattern`

The is expression performs inline pattern matching, evaluating to a boolean. When used in conditional contexts, matched pattern variables are bound in the success branch.

## Terms

A term is a sequence of words:

```
term ::= word {word}
```

!!! info
    When discussing syntactic levels, we use "expression" to mean terms. The grammar in [syntax-summary.md](syntax-summary.md) uses `expr` for this syntactic category.

In word-based syntax, function application is word sequencing:

```jo
add 1 2
println "hello"
list.map(x => x + 1)
```

### Multiline Terms

Terms continue across lines in two cases:

1. **Indented continuation**: When a term is followed by an indented line, the indented portion is parsed as a block. Each phrase in the block becomes a single word in the term.

2. **Operator continuation**: A line beginning with a binary operator continues the previous term. No further indentation is permitted after an operator line.

```jo
// Indented continuation
gcd
  10
  15

// Operator continuation
println
  y < 100
  || z == 5   // continues the previous line, not a new phrase
```

## Phrases

A phrase is a syntactic element that may appear in a block:

```
phrase ::= simple_phrase | assignment | definition | control_flow
simple_phrase ::= term [modifier]
modifier ::= type_ascription | with_clause | allow_clause
```

### Term Modifiers

Terms may be modified by:

- **Type ascription**: `term "as" type`
- **With clause**: `term "with" binding {"," binding}`
- **Allow clause**: `term "allow" (qualid {"," qualid} | "none")`

Modified terms are expressions (produce values).

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

## Blocks

A block is a sequence of phrases:

```
block ::= {phrase}
```

Blocks are delimited by indentation. The `begin...end` construct provides explicit delimiters.

### Implicit Blocks

Several constructs automatically start implicit blocks:

- `while` ... `do`
- `if` ... `then` and `else`
- `val` ... `=`
- `def` ... `=`
- `pattern` ... `=`
- `case` ... `=>`
- Lambda `=>`

The indentation of an implicit block is determined by the indentation of its first phrase. All phrases in a block must be vertically aligned.

### Block Values

A block is always an expression. Its value is determined by its final phrase:

- If the final phrase is an expression, the block evaluates to that value
- If the final phrase is a statement, a unit value is synthesized

## Grammar

For the complete grammar, see [syntax-summary.md](syntax-summary.md).

For detailed specifications:

- Pattern syntax: [pattern-language.md](pattern-language.md)
- Type syntax: [types.md](types.md)
- Definitions: [definitions.md](definitions.md)
- Names and scoping: [names.md](names.md)
