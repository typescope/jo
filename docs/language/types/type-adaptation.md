# Type Adaptation

Type adaptation is the process of converting a value to an expected type when direct type conformance does not hold.

Jo uses adaptation in a controlled, type-directed way. It is not general implicit conversion.

## When Adaptation Applies

Adaptation only runs when **both** of the following are true:

1. The **target type** is fully known
2. The **current word type** is fully known

If either side is still unknown, polymorphic, or only partially instantiated, Jo does not perform type adaptation. In that case, the compiler continues with inference or reports a type error.

This restriction is important:

- It keeps inference and adaptation separate
- It avoids surprising conversions during type inference
- It ensures adaptation is predictable and local

## Direct Match First

Adaptation never runs when the value already conforms to the expected type:

```jo
val x: Int = 42  // direct match, no adaptation
```

Jo first checks normal type conformance. Adaptation is only considered if that fails.

## Common Adaptation Contexts

Adaptation is driven by an expected type, so it appears in places such as:

- function arguments
- annotated values
- return expressions
- branches checked against a known expected type

Example:

```jo
type StringLike = String :- [.toString]

def printLine(s: StringLike): Unit =
  println s

printLine(42)  // adapts through .toString
```

## Built-in Adaptations

Jo includes a small set of built-in adaptations.

### Numeric Adaptation

Some numeric widening conversions are supported when the target type is known:

```jo
val c: Char = 'a'
val i: Int = c
val f: Float = i
```

These are explicit compiler-supported adaptations, not subtype relationships.

### Unit Adaptation

When a `Unit` value is expected, Jo inserts a `Unit` value.

This is used to make control-flow constructs and expressions fit ordinary value positions when the target is `Unit`.

## Type-Directed Adaptation Mechanisms

Jo supports several adaptation mechanisms depending on the target type.

### Duck Type Adapters

Duck types can carry adapter lists:

```jo
type Printable = String :- [.toString]
```

When a value is checked against `Printable`, Jo tries the adapters in order after direct conformance fails.

See [Duck Types](duck-types.md).

### Lambda Interface Adaptation

A lambda may adapt to an interface with a single abstract method when the shapes are compatible.

See [Lambda Types](lambda-types.md).

### Nullary Thunk Adaptation

Jo can adapt a value `e` to an expected nullary function type `() => R` by synthesizing:

```jo
() => e
```

This happens only when the target is a nullary lambda type and the value type already conforms to the result type `R`.

It does **not** recursively trigger further adaptation inside the thunk body.

For example, if `Int` adapts to `StringLike`, that does **not** imply `Int` adapts to `() => StringLike`.

## Adaptation Does Not Chain Arbitrarily

Jo does not treat adaptation as a general graph search.

In particular:

- direct conformance is preferred over adaptation
- adaptation is only attempted in specific expected-type contexts
- adapter mechanisms do not recursively compose

This keeps type checking understandable and error messages local.

## Relation to Type Inference

Type inference and type adaptation are separate phases of reasoning.

- **Inference** determines unknown types
- **Adaptation** converts a fully typed value to a fully known expected type

This is why Jo requires both the current word type and the target type to be fully known before adaptation can run.

See [Type Inference](type-inference.md).
