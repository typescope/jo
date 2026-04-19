# Applications

Application expressions apply arguments to functions, types, or indexed structures.

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

### Named Arguments

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

Current limitations (v1):

- Supported only in explicit call syntax `f(...)` / `new C(...)`
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

`word "[" term {"," term} "]"`

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

- [Words](words.md) - Overview of word forms
- [Isolated Terms](isolated-terms.md) - Terms in applications
- [Syntax Summary](../syntax-summary.md) - Complete grammar
