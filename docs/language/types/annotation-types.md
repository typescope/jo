# Annotation Types

## Overview

An annotation type is a type with one or more annotation suffixes:

```jo
String @py.keyword
Int    @py.positional
```

The annotation is a hint directed at the compiler or a specific backend. It does not change the underlying type — a value of type `String @py.keyword` is a `String`. Annotation types are transparent to the type system: subtyping, type inference, and pattern matching all treat `T @annotation` as `T`.

Annotation types use the same `@name` syntax as [definition annotations](../definitions/annotations.md), but appear in type positions rather than before definitions.

## Syntax

```
simple_type = atom_type {"@" qualid ["(" annot_arg {"," annot_arg} ")"]}
atom_type   = qualid | applied_type | fun_type | duck_type | extension_type | "(" type ")"
```

## Semantics

### Transparency

An annotation type `T @a` is identical to `T` for all type-system purposes:

```jo
val x: String @py.keyword = "hello"
val y: String = x   // OK — String @py.keyword conforms to String
val z: String @py.keyword = y   // OK — String conforms to String @py.keyword
```

Annotations are stripped before conformance checking, unification, and type inference. They leave no trace at runtime.

### Composability

Annotation types compose freely. They can be named via type aliases, used in generic positions, and combined with other type forms:

```jo
// Named alias
type Keyword[T] = T @py.keyword

// In a generic interface
interface SortedApi
  def sorted(iterable: Any, reverse: Keyword[Bool] = false): py.Value
end

// In a function type
type Converter = (String @py.keyword) => Unit
```

Because the annotation is carried with the type, it is preserved through aliases and generic instantiation without any special treatment.


