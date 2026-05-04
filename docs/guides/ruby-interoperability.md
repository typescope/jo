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

The Ruby FFI is built around a single escape-hatch type, `rb.Dynamic`, which represents any Ruby object without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.rb` namespace. As all names under `jo` are imported by default, users can directly use `rb.XXX` without any importing when interoperability is enabled.

In practice, Ruby interop usually follows this pattern:

1. Access a Ruby constant with `rb.const(...)`.
2. Construct objects with `.init(...)` or call dynamic members on `rb.Dynamic`.
3. Use `cast[T]` for one-off conversions, or define a typed wrapper interface when the same Ruby API is used repeatedly.

```jo
def main: Unit =
  rb.require("pathname")
  val path = rb.const("Pathname").init("/tmp/demo")
  println(path.callDynamic("exist?").asBool)
```

## Accessing Constants

Ruby's top-level names are constants. Use `rb.const` to access them:

```jo
val math: rb.Dynamic = rb.const("Math")
val argv: rb.Dynamic = rb.const("ARGV")
```

`rb.const` requires a **string literal** — not a variable. The name is emitted verbatim as Ruby code, so `::` works for nested constants:

```jo
val ignoreCase: rb.Dynamic = rb.const("Regexp::IGNORECASE")
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
private def jsonConst: rb.Dynamic =
  rb.require("json")
  rb.const("JSON")
```

## Dynamic Member Access

`rb.Dynamic` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | Ruby equivalent     | Underlying call              |
|-----------------|---------------------|------------------------------|
| `x.foo`         | `x.foo`             | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`         | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`        | `callDynamic("foo", ...)`    |
| `x.init(...)`   | `x.new(...)`        | `init(...)`                  |
| `x[k]`          | `x[k]`              | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`          | `setDynamic(k, v)`           |

The Ruby backend recognises these method calls on `rb.Dynamic` and emits the corresponding Ruby code.

The member name must be a **string literal** and a valid Ruby method name. This is enforced at compile time.

Use `init(...)` when the receiver is a Ruby class or another object that exposes a conventional `new` method:

```jo
rb.require("pathname")
val path: rb.Dynamic = rb.const("Pathname").init("/tmp/foo")
```

**Limitation — `?` and `!` methods.** Jo identifiers cannot contain `?` or `!`, so predicate methods (`exist?`, `empty?`, `include?`) and mutating methods (`sort!`, `map!`) cannot be called via dot syntax. Use `callDynamic` explicitly instead:

```jo
if path.callDynamic("exist?").asBool then ...
if path.callDynamic("file?").asBool then ...

rb.dynamic(xs).callDynamic("sort!")
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
  val obj = rb.const("OpenStruct").init()
  obj.name = "Jo"                              // attribute write
  val name: String = obj.name.asString         // attribute read
```

```jo
val h = rb.hash("x" ~ 1, "y" ~ 2)
h["z"] = 3                                   // item write
val v: rb.Dynamic = h["x"]                     // item read
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets an `rb.Dynamic` as a Jo type without any runtime conversion. The programmer asserts that the underlying Ruby value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.cast[Int]
val s: String = someValue.cast[String]
```

### Convenience cast shortcuts

`rb.Dynamic` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a Ruby `String`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`rb.Dynamic` implements `toString`, which calls Ruby's `to_s` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `rb.Dynamic` works transparently in string concatenation and `println`:

```jo
val result: rb.Dynamic = rb.const("Math").sqrt(2.0)
println("√2 = " + result)    // calls to_s automatically
println result                // works directly
```

### Wrapping a Jo value

`rb.dynamic(v)` converts any Jo value back to `rb.Dynamic` for dynamic access. This is useful when you have a typed value but need to call a method not in its static interface:

```jo
val arr: rb.Array = rb.array(1, 2, 3)
rb.dynamic(arr).reverse()    // reverse() not in rb.Array, call dynamically
```

## nil Handling

`rb.nil` is Ruby's `nil`. Test for it with `rb.isNil` or the `isNil` method on `rb.Dynamic`:

```jo
val result: rb.Dynamic = someHash["missing_key"]

if result.isNil then
  println "not found"
else
  println("found: " + result)
```

`rb.isIdentical(a, b)` maps to Ruby's `equal?` — identity comparison, not equality:

```jo
if rb.isIdentical(result, rb.nil) then ...   // equivalent to result.equal?(nil) in Ruby
```

## Container Types

Jo provides typed interfaces for Ruby's two core container types.

### `rb.Array` — mutable sequence

```jo
val xs: rb.Array = rb.array(1, 2, 3)

xs.push(4)
xs[1] = 9

val first: rb.Dynamic = xs[0]
val size:  Int      = xs.size

val popped: rb.Dynamic = xs.pop()    // removes last element
xs.clear()
```

Bracket syntax works directly on `rb.Array` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: rb.Dynamic = xs[0]
```

### `rb.Hash` — mutable mapping

```jo
val d: rb.Hash = rb.hash("x" ~ 1, "y" ~ 2)

d["z"] = 3
val v: rb.Dynamic = d["x"]

val hasY: Bool   = d.contains("y")
val size: Int    = d.size

val keys:   rb.Dynamic = d.keys()
val values: rb.Dynamic = d.values()

val rm: rb.Dynamic = d.delete("x")
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

`rb.Error` exposes three fields:

| Field | Description |
|-------|-------------|
| `e.message` | Exception message string (Ruby `e.message`) |
| `e.typeName` | Ruby exception class name, e.g. `"ArgumentError"` |
| `e.fullMessage` | Full formatted report: class, message, and backtrace (Ruby `e.full_message`) |

```jo
match rb.try(json.parse("[bad"))
  case Ok(v)  => println(v)
  case Err(e) =>
    println("type: " + e.typeName)
    println("msg:  " + e.message)
    println(e.fullMessage)
```

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

Typed wrappers replace `rb.Dynamic` with concrete Jo types at the boundary of a Ruby library. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the Ruby backend still resolves calls dynamically.

**`@rb.interop` is required on every typed wrapper interface.** It tells the Ruby backend to resolve all abstract method calls dynamically and enables the vararg conventions described below. Without it, `@rb.targetName` is rejected and vararg parameters are not unpacked.

There are two complementary techniques for wrapping a Ruby object.

### Interface cast — zero implementation

Declare an interface that matches the Ruby object's shape, then cast the constant directly to it. No method bodies are needed: the Ruby backend resolves every call dynamically at runtime.

```jo
@rb.interop
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

For typed wrappers, the Ruby backend provides `@rb.targetName("...")` for the
main remaining naming mismatch.

**`@rb.targetName("...")`.** Use this when the Jo-facing member name differs
from the Ruby method name. This is especially useful for Ruby methods ending
in `?` or `!`, which Jo identifiers cannot spell directly:

```jo
@rb.interop
interface Pathname
  @rb.targetName("exist?")
  def exists(): Bool

  @rb.targetName("file?")
  def isFile(): Bool

  @rb.targetName("directory?")
  def isDir(): Bool
end
```

The backend lowers `exists()`, `isFile()`, and `isDir()` to Ruby method calls
to `exist?`, `file?`, and `directory?`.

**Keyword-only arguments.** Ruby 3 enforces a strict separation between positional and keyword arguments. For Ruby methods that declare keyword-only parameters (`key:` or `key: default` in the Ruby signature), annotate the parameter type with `@rb.keyword`. The backend then emits `key: value` syntax regardless of whether the caller uses named or positional Jo syntax:

```jo
@rb.interop
interface Formatter
  def format(template: String, width: Int @rb.keyword = 8, align: String @rb.keyword = "left"): String
end

// Both call styles produce correct Ruby keyword syntax:
fmt.format("hello", width = 10, align = "right")  // → fmt.format("hello", width: 10, align: "right")
fmt.format("hello", 10, "right")                   // → fmt.format("hello", width: 10, align: "right")
```

When the keyword parameter has a default, declare it on the Jo side. The backend synthesizes and forwards the default as a keyword argument too:

```jo
fmt.format("hi")   // → fmt.format("hi", width: 8, align: "left")
```

**Renaming keyword arguments.** When the Ruby keyword name is also a Jo keyword (e.g. `type`, `end`, `class`), it cannot be used as the Jo parameter name directly. Pass the Ruby name to `@rb.keyword` and give the parameter a valid Jo name:

```jo
@rb.interop
interface Classifier
  def classify(value: String, kind: String @rb.keyword("type") = "unknown"): String
end

cls.classify("ruby", kind = "language")  // → cls.classify("ruby", type: "language")
cls.classify("42")                        // → cls.classify("42", type: "unknown")
```

**Positional-only parameters.** Some Ruby methods raise `ArgumentError` when called with keyword syntax. Annotate the parameter type with `@rb.positional` to ensure the backend always emits a plain positional argument, stripping any named-argument key from the Jo call site:

```jo
@rb.interop
interface IndexedArray
  def at(index: Int @rb.positional): rb.Dynamic   // → arr.at(n), never arr.at(index: n)
end

arr.at(index = 0)   // named in Jo → positional in Ruby: arr.at(0)
arr.at(0)           // positional in Jo → positional in Ruby: arr.at(0)
```

### Vararg arguments

`@rb.interop` interfaces support three calling conventions for vararg parameters. The convention is chosen by the element type of the vararg:

| Vararg type | Positional args | Splice `..xs` | Keyword `k: v` |
|-------------|:-----------:|:-----------:|:-----------:|
| `..T` | ✓ | ✓ | ✗ |
| `..Mixed[T]` | ✓ | ✓ | ✓ |
| `..Named[T]` | ✗ | ✗ | ✓ |

**`..T` — positional varargs.** Use when the Ruby method accepts only positional `*args`. Individual values and splices are both supported:

```jo
@rb.interop
interface FileModule
  def join(parts: ..String): String
end

f.join("tmp", "a", "b")    // emits: f.join("tmp", "a", "b")
f.join(..segs)              // emits: f.join(*rb.array(segs))
```

**`..Mixed[T]` — positional, splice, and keyword.** Use when the Ruby method accepts both `*args` and keyword parameters. Import `jo.compile.Mixed` to use this type:

```jo
import jo.compile.Mixed

@rb.interop
interface LoggerObj
  def log(parts: ..Mixed[Any]): String
end

logger.log("hello", "world", sep = "|")  // emits: logger.log("hello", "world", sep: "|")
logger.log(..words)                       // emits: logger.log(*rb.array(words))
```

**`..Named[T]` — keyword-only.** Use when the Ruby method accepts only keyword parameters. Import `jo.compile.Named` to use this type:

```jo
import jo.compile.Named

@rb.interop
interface Configurator
  def configure(opts: ..Named[Any]): String
end

cfg.configure(x = 1, y = 2)    // emits: cfg.configure(x: 1, y: 2)
```

**`**hash` spreading is intentionally not supported.** Ruby allows `method(**opts)` to spread a Hash as keyword arguments, but this is not exposed in the Jo interop layer. When calling a known Ruby API, the keyword argument names are always known at the call site, so `k = v` syntax in `..Mixed[T]` or `..Named[T]` covers every case. Spreading an opaque runtime Hash would undermine the interop contract.

