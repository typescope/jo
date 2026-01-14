# Words

A word is an atomic syntactic unit in Jo's expression language.

## Literals

Constant values written directly in source code: integers (`42`), booleans (`true`), characters (`'a'`), strings (`"hello"`), and lists (`[1, 2, 3]`).

See [Literals](literals.md) for detailed information.

## Identifiers

```
identifier ::= (letter | "_") {letter | digit | "_"}
operator   ::= opchar {opchar}
qualid     ::= identifier {"." identifier}
```

Examples: `x`, `counter`, `List.map`, `+`, `&&`

### Simple Identifiers

```jo
val x = 10
val userName = "Alice"
val is_valid = true
```

### Qualified Identifiers

```jo
List.map
String.fromInt
Math.sqrt
```

### Operators

```jo
1 + 2
x && y
list ++ otherList
```

## Fence

`"(" phrase ")"`

Parentheses group expressions and override precedence:

```jo
(x + y) * z
(list.filter isPositive)
(if condition then a else b)
```

## Lambda

Anonymous functions that can be passed as values: `x => x + 1`, `(x, y) => x + y`.

Lambdas can capture variables from their surrounding scope and automatically adapt to interface types with a single abstract method.

See [Lambdas](lambdas.md) for detailed information.

## Applications

Apply arguments to functions, types, or indexed structures:

- **Function Application**: `word "(" [args] ")"` - Call functions
- **Type Application**: `word "[" types "]"` - Apply type arguments
- **Bracket Application**: `word "[" terms "]"` - Index access

See [Applications](applications.md) for detailed information.

## Selection

`word "." identifier`

Access object members:

```jo
point.x
list.length
Math.sqrt

// Chaining
user.address.zipCode
list.head.value
```

## New Expression

`"new" qualid [type_args] [args]`

Create class instances:

```jo
new Point(10, 20)
new Array[Int](100)
new HashMap[String, Int]

// With type parameters
new Box[String]("hello")
new Container[Int, String](42, "answer")
```

## Begin Block

`"begin" block "end"`

Explicit block expression (requires `end`):

```jo
begin
  val x = 10
  val y = 20
  x + y
end

// Nested begins
begin
  val outer = 5
  begin
    val inner = 10
    outer + inner
  end
end
```

!!!warning
    Unlike other constructs where `end` is optional, `begin` requires a matching `end` marker. This is intentional: the explicit use of `begin` signals the programmer's intent to explicitly mark a region of code, and a missing `end` would be inconsistent with that intent.

## Is Expression

Pattern matching that returns a boolean: `value is Some(x)`, `list is [first, ..rest]`.

Matched variables are bound in the success branch of conditionals, allowing pattern-based type tests and destructuring.

See [Is Expression](is-expression.md) for detailed information.

## See Also

- [Literals](literals.md) - Constant values and collection literals
- [Applications](applications.md) - Function, type, and bracket applications
- [Lambdas](lambdas.md) - Anonymous functions
- [Is Expression](is-expression.md) - Pattern matching expressions
- [Isolated Terms](isolated-terms.md) - Sequences of words
- [Syntax Summary](../syntax-summary.md) - Complete grammar
