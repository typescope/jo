# Error Handling

Most error-handling complexity in mainstream languages stems from a single mistake:
treating bugs and recoverable errors as the same kind of thing.

**Bugs** — violated invariants, out-of-bounds accesses, programmer mistakes — should
panic immediately. Recovery is not meaningful; the process is in an inconsistent state.
Trying to catch and handle them (à la `NullPointerException`) creates false confidence
without restoring correctness.

**Recoverable errors** — item not found, parse failure, network timeout — are expected
conditions that need to be tolerated gracefully. They should be values, handled explicitly at the call site.

Jo enforces this separation at the language level. (See Duffy,
[*The Error Model*](https://joeduffyblog.com/2016/02/07/the-error-model/), 2016.)

## Why Not Exceptions

Java's checked exceptions tried to enforce error handling at the type level but
introduced two fatal problems (Hejlsberg,
[*The Trouble with Checked Exceptions*](https://www.artima.com/articles/the-trouble-with-checked-exceptions), 2003):

- **Versionability**: adding a new failure mode requires updating every `throws` clause
  in the call chain — analogous to adding a method to an interface.
- **Scalability**: declared exceptions explode when aggregating subsystems; developers
  resort to `throws Exception` or empty catch blocks, defeating the feature.

Beyond the type-system problems, exceptions break local reasoning: any call may jump to
a distant `catch`, bypassing all the code in between. And they make it natural to catch
bugs the same way as recoverable errors, which is exactly what you shouldn't do.

## Why Not Monadic Chaining

`.flatMap`/`.map` chains are type-safe but come with costs: the happy path is buried in
higher-order functions, `return` inside a lambda is either forbidden or requires special
handling, and converting between error types at boundaries requires explicit wrapping at
every step.

## Explicit vs. Implicit Propagation

**Rust's `?`** makes early return implicit — the `return` is compiler-inserted after
error-type conversion. The `?` is visible but the control-flow jump is not.

**Jo's `rescue`** requires the `return` to be written explicitly in the handler block.
Both the `rescue` keyword and the `return` are visible to a reader scanning for early
exits — consistent with the principle that control flow should always be explicit.
`rescue` also handles error *correction* (default values, fallbacks) with the same
syntax, not just propagation.

## Recoverable Errors as Values

Recoverable errors are union-typed values:

```jo
union Option[T] = None | Some(value: T)
union Result[T, E] = Ok(value: T) | Err(error: E)
```

The union type enforces handling: you cannot use the value without covering all
branches. `rescue` handles the error branch inline, keeping the success path linear:

**Default value:**
```jo
val host = config.get("host") rescue None => "localhost"
```

**Propagate to caller:**
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

For multiple error variants, collect them under a shared union — the top-level type
remains two-branch:

```jo
union AppError = NotFound | Unauthorized | ServerError(msg: String)

val data: Data = fetchData(id) rescue err: AppError => return err
```

## Dropped Values

Silently discarding a non-`Unit` return value is almost always a bug — especially a
union that represents a possible error. The compiler warns:

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

Both raw union types and `Option[T]`/`Result[T, E]` represent fallible values. Choose
based on visibility:

**Public API return types: prefer `Option[T]` or `Result[T, E]`**

```jo
def matchFirst(input: String): Option[Match]
def compile(source: String): Result[Regex, String]
```

`Some`, `None`, `Ok`, and `Err` are auto-imported everywhere. Callers pattern-match
without extra imports and without knowing the concrete success type name.

**Internal return types: prefer raw union types**

```jo
private def findAt(regex: Regex, input: String, pos: Int): Match | None
```

Within a module the types are in scope; the raw union is more direct and avoids the
wrapper allocation.

**Parameter types: prefer union types**

```jo
def createUser(name: String, email: String | None): User = ...

createUser("Alice", "alice@example.com")   // raw String — no wrapping needed
createUser("Bob", email = None)
```

`T` is a subtype of `T | None`, so callers with a concrete value pass it directly
without wrapping in `Some(...)`.

## See Also

- [Rescue Expression](../language/expressions/rescue-expression.md) — formal specification
