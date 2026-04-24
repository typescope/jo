# Annotation Definitions

Annotations attach compile-time metadata to definitions. They are consumed by the compiler and backends; they have no runtime representation.

## Defining an Annotation

Annotations are declared at the top level with the `annotation` keyword:

```jo
annotation deprecated(message: String)
annotation targetName(name: String)
annotation property
```

Parameter types must be `Int`, `String`, or `Bool`. This is enforced by the type checker, not the parser.

Zero-parameter annotations need no parentheses at either the definition or the use site.

## Using Annotations

Annotations appear immediately before a definition, prefixed with `@`.

They are permitted only on:

- top-level definitions
- members declared directly in a class body
- members declared directly in an interface body

They are not permitted on:

- parameters
- local definitions inside function bodies
- local `val` or `var` bindings

```jo
@deprecated("use newMethod instead")
def oldMethod(): Unit = ...

@targetName("exist?")
def exists(): Bool

@property
def shape(): py.Value
```

Multiple annotations on the same definition are stacked:

```jo
@property
@targetName("is_dir")
def isDir(): Bool
```

The same annotation may be applied at most once to the same definition. Applying `@targetName(...)` twice, for example, is an error even if the arguments are identical.

## Argument Rules

Arguments must be literals — string, integer, or boolean constants. Variables, function calls, and expressions are not permitted.

## Name Resolution

Annotation definitions occupy the term namespace — a name clash between an `annotation` and a `def` is an error, just like any other duplicate definition.

At use sites, `@` triggers a separate annotation lookup that only finds `annotation` declarations. Normal term lookup ignores annotation definitions entirely:

- `@someFunc` is an error if `someFunc` is a regular `def`, not an annotation.
- `myAnnotation(...)` in expression position is an error if `myAnnotation` is an annotation, not a regular `def`.

At present, annotation definitions are restricted to the `jo` namespace. User-defined annotations outside `jo` are not supported.

## Scope

Annotations may only appear on top-level definitions and on members declared directly in a class or interface body. They are not permitted on parameters or on local definitions inside function bodies.

This mirrors the rule for definition modifiers (`private`, `defer`): both annotations and modifiers are restricted to top-level and member definitions. Parameters and local definitions carry no compile-time metadata.
