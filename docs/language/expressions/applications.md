# Applications

Application expressions apply arguments to functions, types, or indexed structures.

## Word Juxtaposition

The most basic call form in Jo is word sequencing — writing a function and its arguments side by side without any punctuation:

```jo
add 1 2
List.map f list
println "hello"
```

Juxtaposition parses left-to-right; the number of arguments each function consumes is determined by its binding structure. See [Expression Syntax](../concepts/expression-syntax.md) for how word sequences are organized into application trees, including operator precedence and shape-based binding.

## Function Application

`word "(" [expr {"," expr}] ")"`

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

Jo also supports a phrase-level colon-call syntax for calls written directly as phrases.

## Colon Calls

Colon calls are call syntax introduced by `:` at phrase level.

### Inline Colon Calls

Inline colon calls are shallow:

```jo
println: 3 + 5
send: user, message
```

Inline arguments are regular expressions separated by commas.

Each inline argument is a closed expression (`expr`).

Nested colon calls are not allowed inline:

```jo
foo: bar: 1, 2, 3   // Invalid
```

Use regular delimited calls instead:

```jo
foo: bar(1, 2), 3
```

### Multiline Colon Calls

Multiline colon calls open an indented argument block:

```jo
gcd:
  10
  15

send:
  to = "team@example.com"
  subject = "Status"
  body = "Done"
```

Each aligned item in the indented block is one argument. Each item is an open expression — a word sequence, lambda, nested colon call, dot chain, or open `if`/`match` form.

### Nested Multiline Colon Calls

Nesting is expressed only in multiline colon-call form:

```jo
gcd:
  a + b
  gcd:
    4
    10
```

This makes grouping explicit in layout instead of relying on implicit indentation hacks.

### Colon Calls in Dot Chains

Colon calls may also be used in dot chains:

```jo
fetch(...)
  .success: v => handle(v)
  .error: () => retry()
```

Each continued line starts with `.<name>:` and forms another colon call from
the result of the previous one. The arguments of each continued step may be
inline or multiline.

### Alignment

Sibling argument items in a multiline colon call should be vertically aligned:

```jo
foo:
  a
  b
```

### Scope

Colon calls are phrase syntax, only available in indentation contexts. They are not allowed in delimited contexts such as parentheses, ordinary call arguments, bracket arguments, or collection literals:

```jo
(foo: 1, 2)     // Invalid
bar(foo: 1, 2)  // Invalid
[foo: 1, 2]     // Invalid
```

## Named Arguments

Explicit calls also support named arguments:
```jo
Range(x, y, inclusive = true, step = 1)
connect(host, port, timeout = 30)
```

Named arguments may be reordered:

```jo
add(a = 1, c = 3, b = 2)
```

You can mix positional and named arguments, but positional arguments must come first:

```jo
add(1, c = 3)      // OK
add(c = 3, 1)      // Error
```

Named arguments also work with constructor calls:

```jo
new Greeting(name = "World", salute = "Hello")
```

- Supported in explicit call syntax `f(...)` / `new C(...)`
- Also supported in phrase-level colon calls such as:
  ```jo
  send: to = "team@example.com", subject = "Hello"

  send:
    to = "team@example.com"
    subject = "Hello"
  ```
- Not supported for lambda/function-value calls

### Named Arguments in Varargs

Named arguments are not allowed in a regular vararg call:

```jo
def sum(args: ..Int): Int = ...

sum(x = 1, y = 2)   // Error: named arguments not supported for varargs
```

When the vararg element type is `NamedArg[T]` (from `jo.compile`), named arguments are permitted and each one is wrapped as `namedArg("name", value)` in the list. This lets backends such as Python inspect the name and emit keyword arguments:

```jo
import jo.compile.NamedArg

def call(method: String, args: ..NamedArg[Any]): Any = ...

call("open", "data.txt", mode = "r", encoding = "utf-8")
```

The same rule applies as for regular calls: positional arguments must come before named ones, and the same name may not appear twice:

```jo
call("open", mode = "r", "data.txt")    // Error: positional after named
call("open", mode = "r", mode = "w")    // Error: duplicate name
```

## Type Application

`word "[" type {"," type} "]"`

Apply type arguments to polymorphic functions:

```jo
identity[Int](42)
cast[String]

// Multiple type parameters
pair[String, Int](key, value)

// Nested type arguments
wrap[List[Int]](xs)
```

## Bracket Application

`word "[" expr {"," expr} "]"`

Access elements by index or key. Desugars to a `.get` call:

```jo
array[0]          // array.get(0)
map["key"]        // map.get("key")
matrix[i, j]      // matrix.get(i, j)

// With expressions
array[i + 1]
matrix[row * width + col]
```

### Disambiguation

The syntax `x[...]` is always parsed as bracket application. The compiler then
inspects the type of the receiver at the call site:

- **Polymorphic receiver** → type application. The arguments must be valid types;
  providing an expression is an error.
- **Non-polymorphic receiver** → bracket application, desugared to `.get(...)`.

```jo
identity[Int](42)   // identity is polymorphic → type application
array[0]            // array is not polymorphic → bracket application: array.get(0)
value.cast[String]  // cast is polymorphic → type application
map["key"]          // map is not polymorphic → bracket application: map.get("key")
```

## See Also

- [Expression Forms](expression-forms.md) - Closed and open expression forms
- [Phrases](phrases.md) - What can appear in a block
- [Syntax Summary](../syntax-summary.md) - Complete grammar
