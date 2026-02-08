# Extension Types

Extension types allow adding methods to a type, enabling the dot syntax for method calls.

## Motivation

1. **Union types cannot carry methods.** Without methods, union types cannot use the convenient dot syntax for operations, and cannot participate in adaptation mechanisms that rely on member lookup (such as Jo's [duck types](duck-types.md) with member adapters like `.toString`).

2. **Adding behavior to existing types.** Sometimes you want to add methods to a type you don't control, or enrich a type with domain-specific operations without modifying its definition.

```jo
extension ResultOps(it: Int | String)
  def toString: String =
    match it
      case n: Int => n.toString
      case s: String => s
    end
end

type Result = extend Int | String with ResultOps

val r: Result = "success"
println r  // Result has the extension member `toString`
```

## Syntax

### Extension Type Definition

```
extension_type = "extend" type "with" qualid
```

An extension type is defined using `extend T1 with Ext`, where `Ext` names an extension definition:

```jo
type Option[T] = extend Some[T] | None with OptionOps

type RichResult[T, E] = extend Ok[T] | Err[E] with ResultOps
```

### Extension Definition

```
extension_def = "extension" ident [type_params] "(" ident ":" type ")" {method} ["end"]
```

An extension definition declares methods that become available on the extension type:

```jo
extension OptionOps[T](it: Option[T])
  def isEmpty: Bool = it is None

  def getOrElse(default: T): T =
    match it
      case Some(v) => v
      case None => default
    end
end
```

## Semantics

### Type Equivalence

An extension type `extend T1 with Ext` is equivalent to `T1` for all purposes **except member resolution**:

```jo
type Option[T] = extend Some[T] | None with OptionOps

// Subtyping works as for the base union type
val a: Option[Int] = Some(42)    // OK: Some[Int] <: Option[Int]
val b: Option[Int] = None        // OK: None <: Option[Int]

// Pattern matching works as for the base union type
match a
  case Some(v) => v
  case None => 0
end

// Extension type is interchangeable with its base type
def foo(x: Some[Int] | None): Unit = ...
foo(a)  // OK: Option[Int] is equivalent to Some[Int] | None
```

Extension types do not introduce new runtime representations. At runtime, an extension type value is indistinguishable from a value of the base type.

### Member Resolution

For a value of extension type `extend T1 with Ext`, member resolution proceeds as follows:

1. **Extension lookup**: Search for the member in extension `Ext`. If found, infer the extension's type parameters by matching `T1` against the extension's parameter type, and use the method with those type arguments.
2. **Base type lookup**: Otherwise, search for the member in `T1`.

```jo
type Option[T] = extend Some[T] | None with OptionOps

val opt: Option[Int] = Some(42)
opt.isEmpty       // Step 1: Found in OptionOps → use extension method
opt.getOrElse(0)  // Step 1: Found in OptionOps → use extension method
```

For union types (the primary use case), step 2 never succeeds because union types have no members. The extension methods are the only available members.

Extension methods can call other extension methods through the `it` parameter, because `it` has the extension type:

```jo
extension OptionOps[T](it: Option[T])
  def isEmpty: Bool = it is None

  def isDefined: Bool = ! it.isEmpty  // Calls isEmpty through extension
end
```

### Interaction with Duck Types

Extension methods participate in duck type member adapters, enabling union types to work with adaptation:

```jo
extension OptionOps[T](it: Option[T])
  def toString(auto print: T => String with [[T].toString]): String =
    match it
      case Some(v) => "Some(" + print(v) + ")"
      case None => "None"
    end
end

type Option[T] = extend Some[T] | None with OptionOps

val opt: Option[Int] = Some(42)
println(opt)  // .toString found through extension → "Some(42)"
```

### Interaction with Auto Parameters

Auto parameter member candidates (e.g., `[T].toString`) can resolve to extension methods when `T` is an extension type. This means extension methods are visible not only through direct dot syntax, but also through auto parameter resolution:

```jo
type StringOrInt = extend String | Int with StringOrIntOps

extension StringOrIntOps(it: StringOrInt)
  def toString: String =
    match it
      case s: String => s
      case x: Int => x.toString

class Box[T](value: T)
  def toString(auto show: T => String with [[T].toString]): String =
    "Box(" + show(value) + ")"
end

val box: Box[StringOrInt] = Box(10)
println(box)  // "Box(10)"
```

Here, the member candidate `[T].toString` in `Box` is resolved for `T = StringOrInt`. Since `StringOrInt` is an extension type with a `toString` method from `StringOrIntOps`, the candidate resolves successfully — the auto parameter `show` is synthesized from the extension method.

This interaction is important for making extension types work seamlessly with generic code that relies on auto parameters for ad-hoc polymorphism.

## Type Checking

### Extension Definition

An extension definition is type-checked as a section whose methods each have a pre-parameter:

```jo
// Source
extension OptionOps[T](it: Option[T])
  def isEmpty: Bool = it is None
  def getOrElse(default: T): T = ...
end

// Type-checked as
section OptionOps
  def (it: Option[T]) isEmpty[T]: Bool = it is None
  def (it: Option[T]) getOrElse[T](default: T): T = ...
end
```

Each method gets:

- The extension's parameter (`it: Option[T]`) as a pre-parameter
- The extension's type parameters (`[T]`) prepended to its own type parameters

### Extension Type

The extension type `extend T1 with Ext` is represented internally as:

```
ExtensionType(base: Type, extensions: List[Symbol])
```

where `extensions` is the list of extension method symbols collected from `Ext`. Each symbol's type is a `ProcType` with a pre-parameter. For example, `extend Some[T] | None with OptionOps` produces:

```
ExtensionType(
  base = Some[T] | None,
  extensions = [OptionOps.isEmpty, OptionOps.getOrElse]
)
```

The extension's type arguments are not stored — they are inferred from the base type at each use site by matching the base type against the method's pre-parameter type.

This representation makes member resolution direct: looking up a method on an extension type is a search over the `extensions` list by name. It also makes adaptation straightforward, since the available methods are explicitly listed.

### Validation

When type-checking `extend T1 with Ext`:

1. `Ext` must be a section.
2. For each member of `Ext`:
    - It must have a pre-parameter.
    - The base type `T1` must be assignable to its pre-parameter type.

## Design Rationale

### Why Not Scope-Based Extension Methods?

Languages like Kotlin and C# allow extension methods to be defined anywhere and resolved through imports. This breaks local reasoning — you cannot know what methods are available on a type without checking all imports. Jo's extension types are tied to the type definition itself: `extend T1 with T2` is visible in the type alias. The set of available methods is determined by the type, not by what's in scope.

### Why Not Interface Conformance?

Languages like Rust and Swift allow extensions to add interface (trait/protocol) conformance to existing types. Jo's extension types deliberately do not support this — they add methods but do not make a type conform to an interface.

This is a consequence of extension types not introducing new runtime representations. An extension type value is the same as the base type value at runtime, so there is no place to attach vtable or witness information for interface conformance.

In practice, Jo's duck types and member adaptation already serve this purpose. Generic code that needs a `.toString` method does not require a `Printable` interface — it uses an auto parameter with a member candidate like `[T].toString`. When `T` is an extension type, the member candidate resolves to the extension method, as shown in the [auto parameters interaction](#interaction-with-auto-parameters). This ad-hoc approach achieves the same goal without requiring explicit conformance declarations.

### Why Prefer Extension Methods Over Base Type Methods?

The member resolution rule checks the extension first, then the base type. For union types, this is a non-issue since they have no members. For class types used as the base of an extension, the user has explicitly chosen to extend the type, so preferring extension methods respects that intent. The base type's methods remain accessible through the `it` parameter inside extension methods.

