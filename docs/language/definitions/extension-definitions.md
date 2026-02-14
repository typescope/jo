# Extension Definitions

Extension definitions declare methods that can be attached to types.

They are used by:

1. **Extension types** (`extend T with Ext`)
2. **Class/object extension references** (`extension Ext` inside class/object bodies)

## Syntax

```jo
extension_def = "extension" ident [type_params] "(" ident ":" type ")" {method} ["end"]
```

Example:

```jo
extension OptionOps[T](it: Some[T] | None)
  def isEmpty: Bool =
    match it
      case Some(_) => false
      case None => true
    end
end
```

## Type Parameters and Receiver

An extension definition can declare type parameters and exactly one receiver parameter.

```jo
extension BoxOps[T](it: Box[T])
  def map[S](f: T => S): = ...
end
```

- `T` is the extension type parameter.
- `it` is the receiver value used by methods.
- Methods can still define their own additional type parameters.

## Desugaring

An extension definition is typed like a section whose methods have an extra pre-parameter (the receiver).

Conceptually:

```jo
// Source
extension Ext[T](it: Box[T])
  def foo(x: Int): Int = ...
  def bar[S](f: T -> S): S = ...
end
```

is checked like:

```jo
section Ext
  def [T](it: Box[T]) foo(x: Int): Int = ...
  def [T](it: Box[T]) bar[S](f: T -> S): S = ...
end
```

This is why extension methods can be selected as members while still being regular functions internally.

## Validation at Attachment Sites

Validation depends on where the extension is attached:

1. **Extension type** (`extend T with Ext`):

    - Receiver compatibility is checked against `T`.
    - Override warnings/checks use the optional `override` list on the extension type.

2. **Class definition** (`extension Ext` in class):

    - Receiver compatibility is checked against the class type.
    - No type arguments are written at the attachment site (`extension Ext`, not `extension Ext[Int]`).

The extension definition itself is reusable; base-type compatibility is checked when attached.

## See Also

- [Extension Types](../types/extension-types.md)
- [Class Definitions](class-definitions.md)
