# Expression Forms

A quick reference for all syntactic forms in Jo, organized by the four levels introduced in [Overview](overview.md).

## Words

Words are the smallest expression units. No space is allowed between a word and any immediate suffix (`.field`, `(args)`, `[index]`):

| Form | Example |
|---|---|
| Integer literal | `42`, `-17`, `0xFF` |
| Float literal | `3.14`, `6.022e23` |
| Boolean literal | `true`, `false` |
| Character literal | `'a'`, `'\n'` |
| String literal | `"hello"` |
| List literal | `[1, 2, 3]`, `[]` |
| Regex literal | `` `\d+` ``, `` `(?i)pattern` `` |
| Identifier | `x`, `myVar` |
| Fence | `(expr)` |
| New expression | `new Point(1, 2)` |
| Selection | `list.size` &nbsp;*(no space before `.`)* |
| Call | `f(x, y)` &nbsp;*(no space before `(`)* |
| Bracket access | `arr[i]` &nbsp;*(no space before `[`)* |
| Is expression | `x is Some(n)` |
| Type ascription | `x as Logger` |
| Prefix application | `!flag`, `-n` |

`e as T` asserts that `e` conforms to `T` and changes the static type of the expression
to `T`. It is a compile-time upcast — not a runtime cast. The value is unchanged; only
the static type narrows. The compiler rejects `e as T` if `e`'s type does not conform
to `T`.

See [Literals](literals.md), [Is Expression](is-expression.md), [Regular Expressions](regular-expressions.md) for details.

## Expressions

Expressions are sequences of one or more words, plus a few extended forms:

| Form | Example |
|---|---|
| Word sequence | `add 1 2`, `List.map f xs` |
| Lambda | `x => x + 1`, `(x, y) => x + y` |
| If expression | `if x > 0 then "pos" else "neg"` |
| Inline colon call | `println: "hello"`, `send: to, subject` |
| Indented colon call | `send:`<br>&nbsp;&nbsp;`to = "alice"`<br>&nbsp;&nbsp;`subject = "Hi"` |
| Dot chain | `[1,2,3]`<br>&nbsp;&nbsp;`.exclude(x => x % 2 == 0)`<br>&nbsp;&nbsp;`.materialize` |
| Match | `match x`<br>`case Some(n) => n`<br>`case None => 0` |
| Rescue | `opt rescue None => "default"` |
| Allow / with | `allow IO in ...`, `with logger = f in ...` |

Every one of these forms may appear in essentially any position — a call argument, a list element, a condition, or a block phrase. The single exception is the **inline colon call**, whose arguments are separated by commas on the `:` line. It may not appear directly inside a comma-separated list, because its commas would merge with the list's own (`f(foo: 1, 2)` is ambiguous). Parenthesize it — `f((foo: 1, 2))` — or use the indented colon form, whose arguments carry no comma. See [One restriction: commas do not nest](overview.md#one-restriction-commas-do-not-nest).

See [Applications](applications.md) for colon call and dot chain syntax, [Control Flow](control-flow.md) for `if`, `match`, and `rescue`, [Lambdas](lambdas.md) for lambda syntax.

::: info Multi-line open word sequence
An open word sequence may span several lines when it is broken at an **infix operator** boundary — either a line ends with an operator (trailing), or the next line begins with an infix operator (leading):

```jo
val total =
  100 +
  200 +
  300

val eligible =
  user.active
  && user.verified
  || user.isAdmin
```

A continuation line must be **aligned with, or more indented than**, the first line of the expression; a line that is *less* indented ends it. Continuation also requires an operator boundary, so consecutive statements at the same indentation stay separate:

```jo
println total
println count          // a separate statement: no separating operator
```

The token at the break must be a genuine **infix** operator. A line that merely *begins* with a prefix operator — written with no space before its operand, such as `-x` — does not, on its own, continue the previous line. It does continue when the previous line ends with a trailing infix operator, in which case the prefix expression is its right operand:

```jo
val net =
  gross +
  -fee                 // operand of the trailing `+`, so the line continues
```
:::


## Phrases

A phrase is anything that can appear in a block. Every expression is a valid phrase. Phrase-only constructs — valid only as a block phrase, never as an expression argument or condition — are:

| Form | Example |
|---|---|
| Assignment | `x = 10`, `point.x = 0`, `arr[i] = v` |
| Return | `return n` |
| Break / continue | `break`, `continue` |
| While loop | `while cond do ...` |
| For loop | `for x in xs do ...` |
| Value definition | `val x = 10`, `var count = 0` |
| Auto definition | `auto eq: Eq[Int] = (x, y) => x == y` |
| Pattern definition | `pattern Even = case x if x % 2 == 0` |
| Function definition | `def f(x: Int): Int = x + 1` |

See [Phrases](phrases.md) and [Control Flow](control-flow.md).

## Blocks

A block is a vertically aligned sequence of phrases, introduced by a definition's `=`, by `=>`, or by `then`, `else`, `do`, `in`, or `case =>`. An assignment's `=` and a `return` take a single expression, not a block. See [Blocks](blocks.md).
