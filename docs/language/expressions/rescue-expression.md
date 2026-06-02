# Rescue Expression

The `rescue` expression handles the error branch of a two-branch union type inline,
without a full `match`.

## Syntax

```
rescue_expr ::= atom "rescue" simple_pattern "=>" block
```

## Semantics

Given `e rescue pat => block` where `e` has type `Ta | Tb` (a two-branch union type
after dealiasing):

1. `pat` must match exactly one of the two branches. Call the unmatched branch `Tx`.

2. **Without `.success`** — if the union type does not define a parameterless `.success`
   method, the expression elaborates to:
   ```jo
   match e
     case pat   => block
     case v: Tx => v
   ```
   The result type is `Tx`.

3. **With `.success`** — if the union type defines a parameterless `.success` method,
   the expression elaborates to:
   ```jo
   match e
     case pat => block
     case v   => v.success
   ```
   The result type is the return type of `.success`.

The handler `block` may return a correction value, execute `return` to propagate the
error out of the enclosing function, or perform any other effect.

## Constraints

- The subject must have a **two-branch** union type after dealiasing. Applying `rescue`
  to a non-union type or a union with more than two branches is a compile error.
- The pattern must exhaustively cover exactly one of the two branches.
- The handler block must produce a value of the result type, or exit via `return`.

## The `.success` Convention

When the success branch is a wrapper type (e.g. `Ok(value: T)`), the raw result of
`rescue` without unwrapping would be `Ok[T]`. A union type opts in to automatic
unwrapping by defining a **parameterless** `.success` method (no required post-arguments,
no auto parameters):

```jo
union Result[T, E] = Ok(value: T) | Err(error: E)
  def success: T =
    match this
      case Ok(value) => value
      case _: Err[E] => panic "unreachable"
```

With `.success` defined, `rescue` unwraps the payload automatically:

```jo
// Result type: result is Int, not Ok[Int]
val n: Int = parse(s) rescue err: Err[String] => return err
```

If a `.success` method is found but does not conform to this protocol, the compiler
reports an error.

## See Also

- [Control Flow](control-flow.md) — `rescue` in the context of other control-flow forms
- [Error Handling](../../guides/error-model.md) — patterns and conventions for error handling
