---
author: Fengyun Liu
status: Draft
created: 2026-09-18
title: Lambda types in union types
---

# JIP 0004 — Lambda types in union types

<JipMeta />

## Summary

This proposal allows a union type to contain one lambda type as a branch:

```jo
type Handler = String | (Int => Int)

def run(h: Handler): Int =
  match h
    case s: String => s.size
    case f: (Int => Int) => f(3)
  end
end
```

A union type may have at most one lambda branch, in the same way as it may have
at most one numeric branch.

## Motivation

A value that is either data or a computation is common in practice. A default
can be a constant or a function that computes it. A handler can be a fixed
response or a callback. A lazy value can be computed already or still pending:

```jo
type Lazy = Int | (() => Int)

def force(v: Lazy): Int =
  match v
    case n: Int => n
    case f: (() => Int) => f()
  end
end
```

Today, union branches must be classes, so the lambda has to be wrapped in a
class:

```jo
class Thunk(run: () => Int)

type Lazy = Int | Thunk
```

The wrapper adds nothing but ceremony at every construction and every use site.
It also allocates an extra object for each value.

## Specification

### Well-formedness

A branch of a union type is a class type, a lambda type or another union type.
After nested unions are flattened, a union type contains at most one lambda
type:

```jo
type Ok  = Int | (() => Int)
type Bad = (Int => Int) | (String => Int)   // error: multiple lambda types
```

At runtime, it is only possible to tell whether a value is a lambda. Its
parameter and result types are not available. Two lambda branches could not be
told apart by pattern matching, which is the same reason as for the restriction
to one numeric branch.

### Subtyping

A lambda type conforms to a union type if it conforms to the lambda branch of
the union. A union type conforms to another union type if, in addition to the
class branches, its lambda branch conforms to the lambda branch of the other.

### Pattern matching

A lambda branch is matched with a type pattern on a lambda type. As the runtime
test only checks whether the value is a lambda, the pattern is valid only when
the lambda branch of the scrutinee type conforms to the pattern type:

```jo
def run(h: String | (Int => Int)): Int =
  match h
    case f: (String => Int) => 1   // error: Int => Int does not conform to String => Int
    case _ => 0
  end
end
```

A lambda type pattern is rejected when the scrutinee type has no lambda branch,
e.g. when it is `Any`. Exhaustivity checking treats the lambda branch like any
other branch.

### Type inference

This proposal does not change type inference. The parameter types of a lambda
literal are not inferred from the lambda branch of an expected union type, so
they are written explicitly where they are needed:

```jo
run((x: Int) => x + 1)
```

Inference from the lambda branch may be considered in a future proposal.

## Implementation

The backends need no new value representation. Lambdas are already values that
erase to `Any` like other union branches.

The only missing piece is the runtime test. A match on a union type is lowered
to class tests, but lambdas have no class. Instead, the pattern matcher lowers a
lambda type test to a call to `isLambdaValue(v: Any): Bool`. Each backend runtime
declares this function as a private intrinsic, so user code cannot depend on it,
and each code generator inlines it:

| Backend     | Test                          |
|-------------|-------------------------------|
| JavaScript  | `typeof v == "function"`      |
| Ruby        | `v.is_a?(Proc)`               |
| Python      | `callable(v)`                 |
| Native      | class id of the closure tag   |
| Interpreter | the value is a closure        |

In the native backend, closures gain a class id header, like class instances.
The class id belongs to a tag class of the runtime that is never instantiated.
