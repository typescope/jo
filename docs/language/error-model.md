# Error Model

Jo separates two failure categories: **bugs** and **recoverable errors**.

**Bugs** — violated invariants, out-of-bounds accesses, programmer mistakes — trigger an immediate panic and are not recoverable. Recovery is not meaningful; the process is in an inconsistent state.

**Recoverable errors** — file not found, parse failure, network timeout — are expected conditions that callers must handle. They are modeled as values and handled explicitly at the call site.

## Recoverable Errors as Values

Recoverable errors are ordinary values of union types:

```jo
union Option[T] = None | Some(value: T)
union Result[T, E] = Ok(value: T) | Err(error: E)
```

Custom error types follow the same pattern:

```jo
union ParseResult = ParseOk(ast: Expr) | ParseErr(msg: String, line: Int)
```

The union type prevents the value from being used without handling all branches. No `throws` declarations, no exception hierarchies.

## The `rescue` Expression

Manual pattern matching on every fallible call produces deeply nested code. The `rescue` expression provides concise, explicit error propagation and correction. When the scrutinee union type defines a parameterless `.success` method, the result is automatically unwrapped to its payload — a convention used by the standard library types `Option[T]` and `Result[T, E]`, and available to any user-defined union type.

### Syntax

```
open_expr ::= ... | atom "rescue" simple_pattern "=>" block
```

### Semantics

Given `e rescue pat => block` where `e : Ta | Tb` (a **two-branch** union type after dealiasing):

1. `pat` must match exactly one of the two branches. Call the unmatched branch `Tx`.
2. If the union type does **not** define a parameterless `.success` method, the expression elaborates to:
   ```jo
   match e
     case pat    => block
     case v: Tx  => v
   ```
   The result type is `Tx`.

3. If the union type defines a parameterless `.success` method, the expression elaborates to:
   ```jo
   match e
     case pat => block
     case v   => v.success
   ```
   The result type is the return type of `.success`.

The handler `block` is an ordinary block. It may return a correction value, execute `return` to propagate the error out of the enclosing function, or perform any other effect.

### Constraints

- The subject expression must have a **two-branch** union type after dealiasing. Applying `rescue` to a non-union type or a union with more than two branches is a compile error.
- The pattern type must equal the type of one of the two branches, and the pattern must exhaustively cover that branch.
- The handler block must produce a value of the result type, or exit via `return`, `break`, or `continue`.

A direct two-branch union works without any wrapper:

```jo
val n: Int = parseInt(s) rescue err: ParseError => return err
// parseInt: String => Int | ParseError
```

For multiple error variants, collect them under a shared error type — the top-level union remains two-branch:

```jo
union AppError = NotFound | Unauthorized | ServerError(msg: String)

val data: Data = fetchData(id) rescue err: AppError => return err
// fetchData: Int => Data | AppError
```

### The `.success` Convention

When the success branch is a wrapper type (e.g. `Ok(value: T)`), the raw result of `rescue` would be `Ok[T]`. If the union type defines a **parameterless** `.success` method — one with no required post-arguments — the result is unwrapped automatically:

```jo
// Result defines .success: result is Int, not Ok[Int]
val n: Int = parse(s) rescue err: Err[String] => return err
```

User-defined union types opt in by defining `.success` as an extension method with no post-parameters and no auto parameters. If a `.success` method is found but does not conform to this protocol, the compiler reports an error.
### Examples

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

**Convert error type at boundary:**
```jo
val n = parseNumber(s) rescue Err(msg) => return Err(AppError.parse(msg))
```

## Standard Library Integration

`Result` and `Option` are plain union types. The compiler recognises `.success` by name — it is a structural protocol: any union type that defines a parameterless `success` method participates automatically. There is no interface to implement; the lookup is purely by method name on the union type:

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)
  def success: T =
    match this
      case Ok(value) => value
      case _: Err[E] => panic "unreachable"

union Option[T] = None | Some(value: T)
  def success: T =
    match this
      case Some(value) => value
      case None => panic "unreachable"
```

The `rescue` expression works with them because they are two-branch union types that define `.success`. Any user-defined union type gains the same automatic unwrapping simply by defining a parameterless `success` method.

## Dropped Values

Silently discarding a non-Unit return value is almost always a bug. The compiler warns when a value is used in statement position without binding its result:

```jo
fetchData(id)        // warning: value of type Result[Data, AppError] is silently dropped
double(x)            // warning: value of type Int is silently dropped
```

When discarding is intentional, `val _ = expr` suppresses the warning and makes the intent explicit:

```jo
val _ = fetchData(id)   // acknowledged discard, no warning
```

The warning fires for any value type except:

- **`Unit`** — the conventional type for side-effecting functions with no meaningful result.
- **Bottom** — diverging expressions (`abort`, `return`, `break`, `continue`) that never produce a value.
- **Dynamic interop types** — values from FFI calls (e.g. `rb.Dynamic`) that carry no Jo-level semantics; discarding them is idiomatic in backend glue code.

## When to Use `Option[T]`/`Result[T, E]`

Jo's union types and the standard `Option[T]`/`Result[T, E]` wrappers can both represent optional or fallible values. The following conventions govern which to use.

### 1. Public API return types: prefer `Option[T]` or `Result[T, E]`

```jo
def matchFirst(input: String): Option[Match]        // public — Option, import-free
def compile(source: String): Result[Regex, String]  // public — Result
```

`Some`, `None`, `Ok`, and `Err` are auto-imported everywhere. Callers pattern-match with `Some(m)` without needing to know the concrete success type name and without any extra import.

### 2. Internal return types: prefer raw union types

```jo
private def findAt(regex: Regex, input: String, pos: Int): Match | None   // internal
```

Within a module or namespace the types are already in scope, so the raw union is more direct and avoids the wrapper allocation.

### 3. Parameter types: prefer union types

```jo
def createUser(name: String, email: String | None): User = ...

createUser("Alice", "alice@example.com")   // raw String, no wrapping
createUser("Bob", None)                    // explicit absence
```

A value of type `T` is a subtype of `T | None` in Jo, so callers who hold a concrete `T` pass it directly without wrapping in `Some(...)`. `Option[String]` as the parameter type would force callers to write `Some("alice@example.com")` for no benefit.

---

## Background

### Why Separate Bugs from Recoverable Errors

Most error handling complexity stems from conflating the two categories. Catchable bugs create false confidence: code that "handles" `NullPointerException` rarely restores a consistent state. Making recoverable errors into panics sacrifices the ability to respond gracefully. The model requires both mechanisms, used for their respective purposes. (See Duffy, [*The Error Model*](https://joeduffyblog.com/2016/02/07/the-error-model/), 2016.)

### Why Not Exceptions

Java's checked exceptions enforced error handling at the type level but introduced two fatal problems (Hejlsberg, [*The Trouble with Checked Exceptions*](https://www.artima.com/articles/the-trouble-with-checked-exceptions), 2003):

- **Versionability**: adding a new failure mode requires updating the `throws` clause, breaking every caller — analogous to adding a method to an interface.
- **Scalability**: aggregating subsystems causes declared exceptions to explode; in practice, developers resort to `throws Exception` or empty catch blocks, defeating the feature.

Both forms break local reasoning: any function call may throw and jump to a distant `catch` handler, bypassing the code in between. A reader cannot determine which statements in a block will execute without knowing the exception behaviour of every callee. This complicates program invariants — code after a call may assume state that was never established — and resource protocols, where cleanup must run regardless of how a block exits.

An even deeper problem: exceptions make it natural to handle bugs and recoverable errors with the same `try`/`catch` machinery, blurring the distinction that should be kept sharp.

### Why Not Monadic Chaining

Using `.flatMap`/`.map` chains is type-safe but carries three practical costs: (1) higher-order functions obscure the primary logic path; (2) `return` inside a lambda is either forbidden or requires special handling; (3) converting between error types mid-chain requires explicit wrapping at every step. The `rescue` expression provides the same type safety without these costs.

### Explicit vs. Implicit Propagation

**Rust's `?`** makes the early return implicit: the `?` token is visible at the call site, but the `return` is not — the compiler inserts it automatically after converting the error.

**Jo's `rescue`** requires any early exit to be an explicit `return` in the handler block. The `rescue` keyword is visible at the call site and the `return` is visible in the handler. This reflects Jo's design principle that control flow should always be explicit: a reader scanning for error exits searches for `return`, the same keyword used everywhere else.
