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

Annotations appear immediately before a definition, prefixed with `@`. They are permitted on `def`, `pattern`, `class`, and `interface` definitions only — not on `val` or `var`:

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

## Argument Rules

Arguments must be literals — string, integer, or boolean constants. Variables, function calls, and expressions are not permitted.

## Name Resolution

Annotation definitions occupy the term namespace — a name clash between an `annotation` and a `def` is an error, just like any other duplicate definition.

At use sites, `@` triggers a separate annotation lookup that only finds `annotation` declarations. Normal term lookup ignores annotation definitions entirely:

- `@someFunc` is an error if `someFunc` is a regular `def`, not an annotation.
- `myAnnotation(...)` in expression position is an error if `myAnnotation` is an annotation, not a regular `def`.

## Scope

Annotations may only appear at the top level of a namespace, class body, or interface body — not inside function bodies.
