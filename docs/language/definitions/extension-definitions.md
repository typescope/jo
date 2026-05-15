# Extension Definitions

Extension definitions declare methods that can be attached to types via the `:+` operator.

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

An extension definition desugars to a generated type alias plus a section:

```jo
// Source
extension Ext[T](it: Box[T])
  def foo(x: Int): Int = ...
  def bar[S](f: T -> S): S = ...
end
```

desugars to:

```jo
type Ext[T] = Box[T] :+ [Ext.foo, Ext.bar]

section Ext
  def [T](it: Box[T]) foo(x: Int): Int = ...
  def [T](it: Box[T]) bar[S](f: T -> S): S = ...
end
```

The pre-parameter type in each section method is exactly what the user annotated for `it` —
the original annotation is preserved, not the generated alias. If the user wants cross-method
calls via `it.method`, they annotate `it` with a previously defined type alias:

```jo
type Option[T] = (Some[T] | None) :+ [OptionOps.isEmpty, OptionOps.getOrElse]

extension OptionOps[T](it: Option[T])   // it: Option[T], has extension methods
  def isEmpty: Bool = it is None
  def isDefined: Bool = !it.isEmpty     // works: it has extension type
end
```

## Shadowing

When an extension method has the same name as a member of the base type, the compiler warns
at the generated type alias. Mark the method with `@shadow` to suppress the warning:

```jo
extension BoxOps[T](it: Box[T])
  @shadow def show: String = "BoxOps.show"  // intentionally shadows Box[T].show
  def extra: String = "extra"
end
```

See [Extension Types](../types/extension-types.md) for the `!` marker used in user-written `:+` expressions.

## Validation at Attachment Sites

When an extension definition is attached via a `:+` expression:

- Each method must have a pre-parameter.
- The base type must conform to the method's pre-parameter type.
- Shadow warnings are emitted per method (suppressed by `@shadow` or `!`).

The extension definition itself is reusable; base-type compatibility is checked when attached.

## See Also

- [Extension Types](../types/extension-types.md)
- [Class Definitions](class-definitions.md)
