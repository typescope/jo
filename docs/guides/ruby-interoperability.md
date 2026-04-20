# Ruby Interoperability

Jo compiles to Ruby and provides a typed FFI layer for calling Ruby libraries from Jo code. This guide covers the full interoperability API.

> **Ruby FFI must be explicitly enabled.** The FFI API (`rb.*`) is only available when the compiler flag `--use-runtime-api ruby` is passed at compile time. Without it, any reference to `rb.*` will fail to resolve.
>
> **Do not enable FFI when compiling untrusted code.** `--use-runtime-api ruby` gives compiled code unrestricted access to the Ruby runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.

## Overview

The Ruby FFI is built around a single escape-hatch type, `rb.Value`, which represents any Ruby object without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.rb` namespace. As all names under `jo` are imported by default, users can directly use `rb.XXX` without any importing when interoperability is enabled.

## Accessing Constants and Requiring Libraries

Ruby's top-level names are constants. Use `rb.const` to access them:

```jo
val math: rb.Value = rb.const("Math")
val argv: rb.Value = rb.const("ARGV")
```

`rb.const` accepts a string literal — not a variable. The name is emitted as raw Ruby, so `::` works for nested constants:

```jo
val flag: rb.Value = rb.const("Regexp::IGNORECASE")
```

Standard library modules must be loaded with `rb.require` before their constants become available:

```jo
rb.require("json")
val json = rb.const("JSON")
```

`rb.require` deduplicates: calling it multiple times with the same name emits a single `require` statement.

## Dynamic Member Access

`rb.Value` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | Ruby equivalent     | Underlying call              |
|-----------------|---------------------|------------------------------|
| `x.foo`         | `x.foo`             | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`         | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`        | `callDynamic("foo", ...)`    |
| `x[k]`          | `x[k]`              | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`          | `setDynamic(k, v)`           |

The Ruby backend recognises the method calls `selectDynamic/callDynamic/etc` on `rb.Value` and emits the corresponding Ruby code.

The member name must be a **string literal** and a valid Ruby method name. This is enforced at compile time.

```jo
val math = rb.const("Math")

val pi:   Float = math.PI.asFloat               // constant read via method-like access
val root: Float = math.sqrt(16.0).asFloat       // method call
```

```jo
val h = rb.hash("x" ~ 1, "y" ~ 2)
h["z"] = 3                                       // item write
val v: rb.Value = h["x"]                         // item read
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets an `rb.Value` as a Jo type without any runtime conversion. The programmer asserts that the underlying Ruby value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.asInt
val s: String = someValue.asString
val b: Bool   = someValue.asBool
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

`rb.nil` is Ruby's `nil` value. Test for it with `rb.isNil` or the `isNil` method on `rb.Value`:

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

val hasY: Bool = d.contains("y")
val size: Int  = d.size

val keys:   rb.Value = d.keys()
val values: rb.Value = d.values()

val rm: rb.Value = d.delete("x")
d.clear()
```

## Exception Handling

`rb.try` wraps an expression in a Ruby `begin/rescue` block and returns `Ok(result)` on success or `Err(error)` on a Ruby exception:

```jo
rb.require("json")
val json = rb.const("JSON")

match rb.try(json.parse("[1,2,3]"))
  case Ok(v)  => println("parsed: " + v)
  case Err(e) => println("parse error: " + e.message)
```

`e.message` calls Ruby's `.message` method on the exception, which gives the standard error message.

`rb.try` is intrinsified — the argument is **not** evaluated eagerly. The compiler wraps the call site in a `begin/rescue/end` block, so the expression itself is what's guarded.

## Utility Functions

```jo
rb.len(obj)    // Ruby obj.length → Jo Int
```

## Writing Typed Wrappers

Typed wrappers replace `rb.Value` with concrete Jo types at the boundary of a Ruby library. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the Ruby backend still resolves calls dynamically.

### Interface cast — zero implementation

Declare an interface that matches the Ruby object's shape, then cast the constant directly to it. No method bodies are needed: the Ruby backend resolves every call dynamically at runtime.

```jo
interface MathModule
  def sqrt(x: Float): Float
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

### Concrete adapter methods

The interface cast covers most cases, but some situations require a concrete method body.

**Attribute access.** Ruby attributes that are not methods must be read with `rb.value(this).attr`. Declare a concrete body:

```jo
interface HttpResponse
  def status:  Int    = rb.value(this).status.asInt
  def body:    String = rb.value(this).body.asString
  def headers: rb.Value = rb.value(this).headers
end
```

Without the concrete bodies, the backend would attempt to call `status()`, `body()`, and `headers()` as Ruby methods with parentheses, which may produce a different result or fail.

**Vararg bridging.** Jo varargs (`..Any`) are passed as individual arguments to `callDynamic`. If the Ruby method expects a Ruby array, convert them first:

```jo
interface Logger
  def log(parts: ..Any): Unit =
    val msg = parts.mkString(" ")
    rb.value(this).callDynamic("log", msg)
end
```

## Full Example

```jo
// Access Ruby's standard JSON library
rb.require("json")
val json = rb.const("JSON")

// Parse JSON
val data: rb.Value = json.parse("""{"name": "Jo", "version": 1}""")

// Access fields dynamically
val name:    String = data["name"].asString
val version: Int    = data["version"].asInt

println(name)     // Jo
println(version)  // 1

// Generate JSON
val arr: rb.Array = rb.array("a", "b", "c")
val out: String   = json.callDynamic("generate", arr).asString
println(out)      // ["a","b","c"]
```

> For anything not covered by the typed API — uncommon methods, rarely-used constants, or third-party gems with non-standard conventions — drop down to `rb.const` and call methods dynamically via dot notation, then cast results as needed.
>
> ```jo
> rb.require("uri")
> val uri = rb.const("URI").callDynamic("parse", "https://example.com/path?q=1")
> println(uri.host.asString)    // example.com
> println(uri.path.asString)    // /path
> ```
