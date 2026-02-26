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
- Not supported for vararg calls
- Not supported for lambda/function-value calls

## Type Application

`word "[" type {"," type} "]"`

Apply type arguments to generic types or functions:

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

### Disambiguation

The parser distinguishes between type application and bracket application based on context:

- **Type application**: When the content inside `[...]` is parsed as types
- **Bracket application**: When the content inside `[...]` is parsed as terms/expressions

```jo
List[Int]        // Type application - Int is a type
array[0]         // Bracket application - 0 is a term
identity[Int](5) // Both - [Int] is type application, (5) is function application
```

## See Also

- [Words](words.md) - Overview of word forms
- [Isolated Terms](isolated-terms.md) - Terms in applications
- [Syntax Summary](../syntax-summary.md) - Complete grammar
