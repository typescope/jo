# Lambda Types

Lambda types are the types of anonymous functions. They specify parameter types,
return type, and which context parameters are received at the call site.

## Syntax

```
lambda_type = "(" [param_types] ")" "=>" type [receives_clause]
            | type "=>" type [receives_clause]
receives_clause = "receives" (qualid {"," qualid} | "none")
param_types = type {"," type}
```

Single-parameter form omits the parentheses:

```jo
type Incrementer = Int => Int
type Handler     = (String, Int) => Unit
type Producer    = () => String
type Predicate[T] = T => Bool
type Processor   = String => String receives IO
type Callback    = () => Unit receives logger
```

## Subtyping

Lambda types are covariant in the return type and contravariant in each parameter type:

- `(A => B) <: (A' => B')` if `A' <: A` and `B <: B'`

Generic lambda types with type parameters follow the same rule after instantiation. The
`receives` clause is part of the type: two lambda types with different `receives` clauses
are not subtypes of each other unless the context parameters are compatible.

`receives none` indicates the lambda captures no context parameters and expects none at
call sites. A lambda with `receives none` is a subtype of a matching lambda without a
`receives` clause.

## Context Parameters

Context parameters listed in the `receives` clause are supplied at each **call site**,
not captured at creation:

```jo
type Logger = String => Unit receives IO.stdout

val log: Logger = msg => println("[LOG] " + msg)

log("hello")                                      // IO.stdout from ambient scope
with IO.stdout = customOutput in log("hello")     // IO.stdout overridden at call site
```

Context parameters **not** listed in `receives` are captured at lambda creation time,
like ordinary closure variables:

```jo
type Processor = String => String receives IO

// logger is not in receives — captured at creation
val processor: Processor = msg =>
  logger.log("Processing: " + msg)
  msg.toUpperCase

// IO is provided at each call site
with IO = customIO in processor("hello")
```

## See Also

- [Duck Types](duck-types.md) - Flexible parameter conversion
- [Type Adaptation](type-adaptation.md) - Nullary thunk adaptation
- [Interface Definitions](../definitions/interface-definitions.md) - Lambda literal adaptation to single-abstract-method interfaces
