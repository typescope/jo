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
  def [T](it: Ext[T]) foo(x: Int): Int = ...
  def [T](it: Ext[T]) bar[S](f: T -> S): S = ...
end
```

The TypeDef base uses the original annotation (`Box[T]`), which avoids circularity. The section
pre-param uses the generated alias type (`Ext[T]`), so all sibling extension methods are
available on `it` via dot syntax:

```jo
extension Option[T](it: Some[T] | None)
  def isEmpty: Bool = it is None
  def isDefined: Bool = !it.isEmpty     // works: it has type Option[T]
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

## Section Merging

The generated section has the same name as the extension definition. If a user-defined
`section` of the same name exists in the same scope, the two are merged automatically:

```jo
extension OptionOps[T](it: Some[T] | None)
  def isEmpty: Bool = it is None
end

// Adds factory methods to the same OptionOps namespace — no conflict
section OptionOps
  def from[T](value: T): OptionOps[T] = Some(value)
end

val opt = OptionOps.from(42)
println opt.isEmpty  // false
```

The same applies to union definitions with methods: the generated section merges
with any user-defined section of the same name in the same scope.

## Validation at Attachment Sites

When an extension definition is attached via a `:+` expression:

- Each method must have a pre-parameter.
- The base type must conform to the method's pre-parameter type.
- Shadow warnings are emitted per method (suppressed by `@shadow` or `!`).

The extension definition itself is reusable; base-type compatibility is checked when attached.

## See Also

- [Extension Types](../types/extension-types.md)
- [Class Definitions](class-definitions.md)
