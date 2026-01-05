# Words

A word is an atomic syntactic unit in Jo's expression language.

## Literals

```
integer  ::= ["-"] digit {digit}
boolean  ::= "true" | "false"
character ::= "'" <character> "'"
string   ::= <single-line-string> | <multi-line-string>
```

Examples: `42`, `-17`, `true`, `'a'`, `"hello"`

### Integer Literals

```jo
val count = 42
val negative = -17
val zero = 0
```

### Boolean Literals

```jo
val flag = true
val isReady = false
```

### Character Literals

```jo
val letter = 'a'
val digit = '9'
val newline = '\n'
```

### String Literals

```jo
val message = "Hello, World!"
val multiline = """
  This is a
  multiline string
  """
```

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

## List

`"[" [term {"," term}] "]"`

List literals:

```jo
[1, 2, 3]
["hello", "world"]
[]  // empty list

// Nested lists
[[1, 2], [3, 4]]

// Mixed with expressions
[x + 1, y * 2, z]
```

## Lambda

`(param_section | identifier) "=>" block`

Anonymous functions:

```jo
x => x + 1              // single parameter
(x, y) => x + y        // multiple parameters
() => 42               // zero parameters
(x: Int) => x * 2      // with type annotation
```

### Lambda Interfaces

Lambdas automatically adapt to interface types with a single abstract method:

```jo
interface Predicate[T]
  def test(x: T): Bool
end

val isEven: Predicate[Int] = x => x % 2 == 0

// Use as interface
isEven.test(4)  // true
```

### Lambda Context Parameters

Context parameters in lambda interfaces come from the call site:

```jo
interface Logger
  def log(msg: String): Unit receives IO.stdout
end

val logger: Logger = msg => println(msg)

// Context parameter provided at call site
logger.log("test") with IO.stdout = customOutput
```

## Function Application

`word "(" [term {"," term}] ")"`

Call functions with arguments:

```jo
println("hello")
add(1, 2)
list.map(x => x * 2)

// Multiple arguments
max(10, 20)
connect(host, port, timeout)

// No arguments
getCurrentTime()
```

## Type Application

`word "[" type {"," type} "]"`

Apply type arguments:

```jo
List[Int]
Option[String]
identity[Int](42)

// Multiple type parameters
Pair[String, Int]
Either[Error, Result]

// Nested type applications
List[Option[Int]]
```

## Bracket Application

`word "[" term {"," term} "]"`

Access elements by index or key:

```jo
array[0]
map["key"]
matrix[i, j]

// With expressions
array[i + 1]
matrix[row * width + col]
```

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

## View Access

`word "." "view" "[" type "]"`

Explicit view conversion:

```jo
range.view[Iterator[Int]]
value.view[Comparable[T]]
point.view[Drawable]
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

`word "is" simple_pattern`

Pattern matching that returns boolean:

```jo
value is Some(x)
list is Cons(head, tail)
number is 42

// Type tests
value is Int
obj is String
```

Matched variables are bound in the success branch of conditionals:

```jo
if value is Some(x) then
  println(x)  // x is bound here
end

if list is [first, ..rest] then
  println("First: " + first)
  println("Rest length: " + rest.length)
end
```

## See Also

- [Terms](terms.md) - Sequences of words
- [Syntax Summary](../syntax-summary.md) - Complete grammar
