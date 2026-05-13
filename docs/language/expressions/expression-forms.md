# Expression Forms

Jo expressions are organized in three ascending levels — atoms, words, and expressions — where each level builds on the previous. Expressions themselves split into two forms depending on where they appear.

## Atoms

An atom is the indivisible syntactic unit. No space is permitted between an atom and any immediate suffix (`.field`, `(args)`, `[index]`):

```
atom = integer | boolean | char | float | string | regex
     | "this" | ident
     | "(" expr ")"                              -- fence
     | "new" qualid [targs] [args]               -- class instantiation
     | "[" [expr {"," expr}] "]"                -- list literal
     | atom NS "." NS ident                     -- selection
     | atom NS "(" [args] ")"                   -- call
     | atom NS "[" expr {"," expr} "]"          -- bracket access
```

The no-space requirement (`NS`) is structurally significant: `f(x)` is a call on `f`, while `f (x)` is two words — `f` followed by the fenced expression `(x)`.

Selection, call, and bracket access chain onto any atom, including the result of a previous application:

```jo
list.map(x => x + 1)       // select, then call
matrix[i][j]               // two bracket accesses
obj.method(args).result    // call, then select
```

## Words

A word extends atoms with two additional forms:

```
word = atom
     | atom "is" simple_pattern    -- boolean pattern test
     | SP operator NS atom         -- prefix application
```

A **prefix application** applies a unary operator to an atom: `!flag`, `-n`. The leading space distinguishes it from an infix operator in a word sequence.

An **is expression** tests a value against a pattern and evaluates to a boolean. Variables bound by the pattern become available through flow typing in subsequent conditions and expressions. See [Is Expression](is-expression.md).

**Word sequences** express function application by juxtaposition:

```jo
add 1 2
List.map isPositive numbers
println "hello"
```

## Closed Expressions

A closed expression (`expr`) appears in any delimited context: inside `(...)`, as a call argument, in a string interpolation `\{expr}`, or as an inline binding right-hand side.

```
expr = words                                        -- word sequence
     | (param_section | name) "=>" block            -- lambda
     | "if" words "then" block "else" block         -- if expression
```

**Invariant:** no top-level comma, `=`, or unadorned `:`. These characters terminate a closed expression in their surrounding context.

```jo
// Word sequence as call argument
max(x + 1, y * 2)

// Lambda as call argument
list.map(x => x * 2)

// If expression as call argument (else branch is required)
println(if x > 0 then "positive" else "negative")
```

The `else` branch is required in a closed `if` because both branches must produce a value and the parser needs a definite endpoint.

**Colon calls, open `if`, `match`, `allow`, and `with` are open expressions and cannot appear here directly.** Extract to a `val` binding to use them in a delimited context.

## Open Expressions

An open expression (`open_expr`) appears at phrase level (inside blocks) and as the argument form inside indented colon calls. Its extent is determined by indentation rather than a surrounding delimiter.

```
open_expr = words NL                               -- word sequence (ends at line)
          | (param_section | name) "=>" block      -- lambda with block body
          | colon_call                             -- see Applications
          | dot_chain                              -- see Applications
          | "if" words "then" block ["else" block] ["end"]
          | "match" words {"case" pattern "=>" block} ["end"]
          | "allow" qualid {"," qualid} "in" block
          | "with" qualid "=" expr {"," qualid "=" expr} "in" block
```

### Word sequence

The simplest open expression — a word sequence terminating at the line boundary:

```jo
def main =
  println "hello"
  add 1 2
```

### Lambda

With a block body, the lambda body is a full block of phrases:

```jo
val process = x =>
  val doubled = x * 2
  doubled + 1
```

### Colon call and dot chain

The main indentation-sensitive call forms. See [Applications](applications.md) for full syntax.

```jo
// Inline colon call
println: "hello, world"

// Indented colon call — each indented line is one argument
send:
  to = "team@example.com"
  subject = "Update"

// Dot chain — dots must be first on their line
[1, 2, 3, 4, 5]
  .exclude(x => x % 2 == 0)
  .materialize
```

### Open if and match

Unlike closed `if`, the open form makes `else` optional and uses block bodies:

```jo
if x > 0 then
  println "positive"

match value
case Some(x) => println x
case None    => println "none"
```

### Allow and with

Scope a capability or context binding over a block:

```jo
allow IO in
  println "hello"

with logger = fileLogger in
  process data
```

## See Also

- [Applications](applications.md) — Full syntax for colon calls, dot chains, and all call forms
- [Control Flow](control-flow.md) — `if`, `match`, `while`, `for`, and loop control
- [Lambdas](lambdas.md) — Lambda syntax, closures, and SAM interface adaptation
- [Is Expression](is-expression.md) — Boolean pattern matching and flow typing
- [Syntax Summary](../syntax-summary.md) — Complete formal grammar
