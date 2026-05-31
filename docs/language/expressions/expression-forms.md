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
| Prefix application | `!flag`, `-n` |

See [Literals](literals.md), [Is Expression](is-expression.md), [Regular Expressions](regular-expressions.md) for details.

## Expressions

Expressions are sequences of one or more words, plus a few extended forms:

| Form | Example | Open only |
|---|---|---|
| Word sequence | `add 1 2`, `List.map f xs` | |
| Lambda | `x => x + 1`, `(x, y) => x + y` | |
| If expression | `if x > 0 then "pos" else "neg"` | |
| Colon call | `println: "hello"` | ✓ |
| Indented colon call | `send:`<br>&nbsp;&nbsp;`to = "alice"`<br>&nbsp;&nbsp;`subject = "Hi"` | ✓ |
| Dot chain | `[1,2,3]`<br>&nbsp;&nbsp;`.exclude(x => x % 2 == 0)`<br>&nbsp;&nbsp;`.materialize` | ✓ |
| Match | `match x`<br>`case Some(n) => n`<br>`case None => 0` | ✓ |
| Rescue | `opt rescue None => "default"` | ✓ |
| Allow / with | `allow IO in ...`, `with logger = f in ...` | ✓ |

See [Applications](applications.md) for colon call and dot chain syntax, [Control Flow](control-flow.md) for `if`, `match`, and `rescue`, [Lambdas](lambdas.md) for lambda syntax.

## Phrases

A phrase is anything that can appear in a block. Every expression is a valid phrase. Phrase-only constructs (not valid in delimited positions) are:

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

A block is a vertically aligned sequence of phrases, introduced by `=`, `=>`, `then`, `else`, `do`, `in`, or `case =>`. See [Blocks](blocks.md).
