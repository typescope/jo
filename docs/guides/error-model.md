# Error Handling

Jo separates two failure categories: **bugs** and **recoverable errors**.

**Bugs** — violated invariants, out-of-bounds accesses, programmer mistakes — trigger an
immediate panic and are not recoverable. Recovery is not meaningful; the process is in an
inconsistent state.

**Recoverable errors** — item not found, parse failure, network timeout — are expected
conditions that callers intend to handle gracefully. They are modeled as values and
handled explicitly at the call site.

For the `rescue` expression specification, see
[Rescue Expression](../language/expressions/rescue-expression.md).

## Recoverable Errors as Values

Recoverable errors are ordinary values of union types:

```jo
union Option[T] = None | Some(value: T)
union Result[T, E] = Ok(value: T) | Err(error: E)
```

The union type prevents the value from being used without handling all branches.

## Using `rescue`

`rescue` handles the error branch inline, keeping the success path linear:

**Default value:**
```jo
val host = config.get("host") rescue None => "localhost"
```

**Propagate error to caller:**
```jo
val n = parseNumber(s) rescue err: Err[String] => return err
```

**Log then propagate:**
```jo
val ast: Expr = parseFile(path) rescue Err(msg) =>
  println("parse error: " + msg)
  return Err(msg)
```

**Convert error type at a boundary:**
```jo
val n = parseNumber(s) rescue Err(msg) => return Err(AppError.parse(msg))
```

For multiple error variants, collect them under a shared error union — the top-level
type remains two-branch:

```jo
union AppError = NotFound | Unauthorized | ServerError(msg: String)

val data: Data = fetchData(id) rescue err: AppError => return err
```

## Dropped Values

Silently discarding a non-`Unit` return value is almost always a bug — especially when
the type is a union that represents a possible error. The compiler warns:

```jo
fetchData(id)   // warning: value of type Result[Data, AppError] is silently dropped
```

Use `val _ = expr` to acknowledge an intentional discard:

```jo
val _ = fetchData(id)   // no warning
```

The warning does not fire for `Unit`, bottom expressions (`abort`, `return`, etc.), or
dynamic interop types.

## To Wrap or Not

Both raw union types and the `Option[T]`/`Result[T, E]` wrappers can represent fallible
values. Choose based on context:

**Public API return types: prefer `Option[T]` or `Result[T, E]`**

```jo
def matchFirst(input: String): Option[Match]
def compile(source: String): Result[Regex, String]
```

`Some`, `None`, `Ok`, and `Err` are auto-imported everywhere, so callers pattern-match
without extra imports.

**Internal return types: prefer raw union types**

```jo
private def findAt(regex: Regex, input: String, pos: Int): Match | None
```

Within a module the types are in scope; the raw union is more direct.

**Parameter types: prefer union types**

```jo
def createUser(name: String, email: String | None): User = ...

createUser("Alice", "alice@example.com")   // raw String — no wrapping needed
createUser("Bob", email = None)
```

`T` is a subtype of `T | None`, so callers with a concrete value pass it directly.

## Background

### Why Separate Bugs from Recoverable Errors

Most error handling complexity stems from conflating the two categories. Catchable bugs
create false confidence: code that "handles" `NullPointerException` rarely restores a
consistent state. Making recoverable errors into panics sacrifices the ability to respond
gracefully. (See Duffy, [*The Error Model*](https://joeduffyblog.com/2016/02/07/the-error-model/), 2016.)

### Why Not Exceptions

Java's checked exceptions enforced error handling at the type level but introduced two
fatal problems (Hejlsberg, [*The Trouble with Checked Exceptions*](https://www.artima.com/articles/the-trouble-with-checked-exceptions), 2003):

- **Versionability**: adding a new failure mode requires updating the `throws` clause,
  breaking every caller.
- **Scalability**: declared exceptions explode when aggregating subsystems; developers
  resort to `throws Exception` or empty catch blocks, defeating the feature.

Exceptions break local reasoning: any call may jump to a distant `catch`, bypassing
surrounding code. They also make it natural to handle bugs and recoverable errors with
the same machinery, blurring a distinction that should be kept sharp.

### Why Not Monadic Chaining

`.flatMap`/`.map` chains are type-safe but obscure the primary logic path, forbid `return`
inside lambdas, and require explicit wrapping at every type conversion. `rescue` provides
the same type safety without these costs.

### Explicit vs. Implicit Propagation

**Rust's `?`** makes early return implicit — the `return` is compiler-inserted.

**Jo's `rescue`** requires explicit `return` in the handler. Both the `rescue` keyword
and the `return` are visible to the reader, consistent with Jo's principle that control
flow should always be explicit.
