# Extension Definitions

Extension definitions allow adding behavior to existing types.

## Syntax

```jo
extension_def = "extension" ident [type_params] "(" ident ":" type ")" {method} ["end"]
```

Example:

```jo
extension OptionOps[T] for Some[T] | None
  def isEmpty: Bool =
    match this
      case Some(_) => false
      case None => true
    end
end
```

## Type Parameters

An extension definition can declare type parameters:

```jo
extension BoxOps[T] for Box[T]
  def map[S](f: T => S): = ...
end
```

- `T` is the extension type parameter.
- Methods can still define their own additional type parameters.

## Desugaring

An extension definition desugars to a generated type alias plus a section:

```jo
// Source
extension Ext[T] for Box[T]
  def foo(x: Int): Int = ...
  def bar[S](f: T -> S): S = ...
end
```

desugars to:

```jo
type Ext[T] = Box[T] :+ [Ext.foo, Ext.bar]

section Ext
  def [T](this: Ext[T]) foo(x: Int): Int = ...
  def [T](this: Ext[T]) bar[S](f: T -> S): S = ...
end
```

The pre-parameter uses the generated alias type (`Ext[T]`), so all sibling extension methods are
available on `this` via dot syntax:

```jo
extension Option[T] for Some[T] | None
  def isEmpty: Bool = this is None
  def isDefined: Bool = !this.isEmpty     // works: this has type Option[T]
end
```

## Shadowing

When an extension method has the same name as a member of the base type, the compiler warns
at the generated type alias. Mark the method with `@shadow` to suppress the warning:

```jo
extension BoxOps[T] for Box[T]
  @shadow def show: String = "BoxOps.show"  // intentionally shadows Box[T].show
  def extra: String = "extra"
end
```

See [Extension Types](../types/extension-types.md) for the `!` marker used in manually creating extension types.

## Section Merging

The generated section has the same name as the extension definition. If a user-defined
`section` of the same name exists in the same scope, the two are merged automatically:

```jo
extension Option[T] for Some[T] | None
  def isEmpty: Bool = this is None
end

// Adds factory methods to the same Option namespace — no conflict
section Option
  def from[T](value: T): Option[T] = Some(value)
end

val opt = Option.from(42)
println opt.isEmpty  // false
```

## See Also

- [Extension Types](../types/extension-types.md)
- [Class Definitions](class-definitions.md)
