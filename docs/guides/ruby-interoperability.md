# Ruby Interoperability

::: warning Experimental API
The Ruby FFI API is experimental. All APIs documented here are subject to change in future releases.
:::

Jo compiles to Ruby and provides a typed FFI layer for calling Ruby libraries from Jo code. This guide covers the full interoperability API.

::: warning
**Ruby FFI must be explicitly enabled.** The FFI API (`rb.*`) is only available when the compiler flag `--use-runtime-api ruby` is passed at compile time. Without it, any reference to `rb.*` will fail to resolve.

**Do not enable FFI when compiling untrusted code.** `--use-runtime-api ruby` gives compiled code unrestricted access to the Ruby runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.
:::

## Overview

The Ruby FFI is built around a single escape-hatch type, `rb.Value`, which represents any Ruby object without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.rb` namespace. As all names under `jo` are imported by default, users can directly use `rb.XXX` without any importing when interoperability is enabled.

## Accessing Constants

Ruby's top-level names are constants. Use `rb.const` to access them:

```jo
val math: rb.Value = rb.const("Math")
val argv: rb.Value = rb.const("ARGV")
```

`rb.const` requires a **string literal** — not a variable. The name is emitted verbatim as Ruby code, so `::` works for nested constants:

```jo
val ignoreCase: rb.Value = rb.const("Regexp::IGNORECASE")
val pi:         Float    = rb.const("Math::PI").asFloat
```

## Loading Libraries

Standard library modules that are not autoloaded must be required before use. `rb.require` must be called inside a function body:

```jo
def main: Unit =
  rb.require("json")
  val json = rb.const("JSON")

  rb.require("date")
  val date = rb.const("Date")
```

`rb.require` deduplicates: calling it multiple times with the same name emits a single `require` at the top of the generated file. The name must be a **string literal**.

When wrapping a library for repeated use, call `rb.require` inside the factory function:

```jo
private def jsonConst: rb.Value =
  rb.require("json")
  rb.const("JSON")
```

## Dynamic Member Access

`rb.Value` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | Ruby equivalent     | Underlying call              |
|-----------------|---------------------|------------------------------|
| `x.foo`         | `x.foo`             | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`         | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`        | `callDynamic("foo", ...)`    |
| `x[k]`          | `x[k]`              | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`          | `setDynamic(k, v)`           |

The Ruby backend recognises these method calls on `rb.Value` and emits the corresponding Ruby code.

The member name must be a **string literal** and a valid Ruby method name. This is enforced at compile time.

**Limitation — `?` and `!` methods.** Jo identifiers cannot contain `?` or `!`, so predicate methods (`exist?`, `empty?`, `include?`) and mutating methods (`sort!`, `map!`) cannot be called via dot syntax. Use `callDynamic` explicitly instead:

```jo
val path: rb.Value = rb.const("Pathname").callDynamic("new", "/tmp/foo")

if path.callDynamic("exist?").asBool then ...
if path.callDynamic("file?").asBool then ...

rb.value(xs).callDynamic("sort!")
```

When a `?`/`!` method is called frequently, a typed wrapper with a renamed adapter method is the cleaner solution — see [Writing Typed Wrappers](#writing-typed-wrappers).

```jo
val math = rb.const("Math")

val root: Float = math.sqrt(16.0).asFloat    // method call
val log2: Float = math.log2(1024.0).asFloat
```

```jo
def main: Unit =
  rb.require("ostruct")
  val obj = rb.const("OpenStruct").callDynamic("new")
  obj.name = "Jo"                              // attribute write
  val name: String = obj.name.asString         // attribute read
```

```jo
val h = rb.hash("x" ~ 1, "y" ~ 2)
h["z"] = 3                                   // item write
val v: rb.Value = h["x"]                     // item read
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets an `rb.Value` as a Jo type without any runtime conversion. The programmer asserts that the underlying Ruby value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.cast[Int]
val s: String = someValue.cast[String]
```

### Convenience cast shortcuts

`rb.Value` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a Ruby `String`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`rb.Value` implements `toString`, which calls Ruby's `to_s` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `rb.Value` works transparently in string concatenation and `println`:

```jo
val result: rb.Value = rb.const("Math").sqrt(2.0)
println("√2 = " + result)    // calls to_s automatically
println result                // works directly
```

### Wrapping a Jo value

`rb.value(v)` converts any Jo value back to `rb.Value` for dynamic access. This is useful when you have a typed value but need to call a method not in its static interface:

```jo
val arr: rb.Array = rb.array(1, 2, 3)
rb.value(arr).reverse()    // reverse() not in rb.Array, call dynamically
```

## nil Handling

`rb.nil` is Ruby's `nil`. Test for it with `rb.isNil` or the `isNil` method on `rb.Value`:

```jo
val result: rb.Value = someHash["missing_key"]

if result.isNil then
  println "not found"
else
  println("found: " + result)
```

`rb.isSame(a, b)` maps to Ruby's `equal?` — identity comparison, not equality:

```jo
if rb.isSame(result, rb.nil) then ...   // equivalent to result.equal?(nil) in Ruby
```

## Container Types

Jo provides typed interfaces for Ruby's two core container types.

### `rb.Array` — mutable sequence

```jo
val xs: rb.Array = rb.array(1, 2, 3)

xs.push(4)
xs[1] = 9

val first: rb.Value = xs[0]
val size:  Int      = xs.size

val popped: rb.Value = xs.pop()    // removes last element
xs.clear()
```

Bracket syntax works directly on `rb.Array` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: rb.Value = xs[0]
```

### `rb.Hash` — mutable mapping

```jo
val d: rb.Hash = rb.hash("x" ~ 1, "y" ~ 2)

d["z"] = 3
val v: rb.Value = d["x"]

val hasY: Bool   = d.contains("y")
val size: Int    = d.size

val keys:   rb.Value = d.keys()
val values: rb.Value = d.values()

val rm: rb.Value = d.delete("x")
d.clear()
```

## Exception Handling

`rb.try` wraps an expression in a Ruby `begin/rescue` block and returns `Ok(result)` on success or `Err(error)` on a Ruby exception:

```jo
def main: Unit =
  rb.require("json")
  val json = rb.const("JSON")

  match rb.try(json.parse("[1, 2, 3]"))
    case Ok(v)  => println("parsed: " + v)
    case Err(e) => println("parse error: " + e.message)
```

`e.message` calls Ruby's `.message` method on the exception, which gives the standard error message string.

`rb.try` is intrinsified — the argument is **not** evaluated eagerly. The compiler wraps the call site in a `begin/rescue/end` block, so the expression itself is what's guarded.

::: info
For anything not covered by the typed API — uncommon methods, rarely-used constants, or third-party gems with non-standard conventions — drop down to the low-level mechanism: access the constant with `rb.const`, load it with `rb.require` if needed, call methods dynamically via dot notation, and cast results as needed.

```jo
def main: Unit =
  rb.require("uri")
  val uri = rb.const("URI").parse("https://example.com/path?q=1")
  println(uri.host)     // example.com
  println(uri.path)     // /path
  println(uri.query)    // q=1
```
:::

## Writing Typed Wrappers

Typed wrappers replace `rb.Value` with concrete Jo types at the boundary of a Ruby library. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the Ruby backend still resolves calls dynamically.

There are two complementary techniques for wrapping a Ruby object.

### Interface cast — zero implementation

Declare an interface that matches the Ruby object's shape, then cast the constant directly to it. No method bodies are needed: the Ruby backend resolves every call dynamically at runtime.

```jo
interface MathModule
  def sqrt(x: Float): Float
  def cbrt(x: Float): Float
  def log(x: Float): Float
  def sin(x: Float): Float
  def cos(x: Float): Float
end

def math: MathModule = rb.const("Math").cast[MathModule]
```

User code calls it like any Jo interface:

```jo
println(math.sqrt(2.0))    // emits: Math.sqrt(2.0)
println(math.sin(3.14))
```

The Ruby backend omits parentheses on zero-argument calls, emitting `obj.foo` rather than `obj.foo()`. This matches Ruby convention and means attribute readers defined via `attr_reader` work equally well through the interface cast without any concrete body.

### Concrete adapter methods

The interface cast covers most cases, but two situations require a concrete method body.

**`?`/`!` methods.** Since Jo identifiers cannot contain `?` or `!`, rename the method and call through `rb.value(this)`:

```jo
interface Pathname
  def exists(): Bool      = rb.value(this).callDynamic("exist?").asBool
  def isFile(): Bool      = rb.value(this).callDynamic("file?").asBool
  def isDir(): Bool       = rb.value(this).callDynamic("directory?").asBool
end
```

**Vararg bridging.** Jo varargs (`..Any`) are forwarded as individual positional arguments to `callDynamic`. If the Ruby method expects a Ruby Array, wrap them with `rb.array` first:

```jo
interface Path
  def join(segments: ..Any): String =
    val parts = rb.array(..segments)
    rb.const("File").join(parts).asString
end
```
