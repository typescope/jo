# JavaScript Interoperability

::: warning Experimental API
The JavaScript FFI API is experimental. All APIs documented here are subject to change in future releases.
:::

Jo compiles to JavaScript and provides a typed FFI layer for calling JavaScript libraries from Jo code. This guide covers the full interoperability API.

::: warning
**JavaScript FFI must be explicitly enabled.** The FFI API (`js.*`) is only available when the compiler flag `--use-runtime-api js` is passed at compile time. Without it, any reference to `js.*` will fail to resolve.

**Do not enable FFI when compiling untrusted code.** `--use-runtime-api js` gives compiled code unrestricted access to the JavaScript runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.
:::

## Overview

The JavaScript FFI is built around a single escape-hatch type, `js.Dynamic`, which represents any JavaScript value without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.js` namespace. As all names under `jo` are imported by default, users can directly use `js.XXX` without any importing when interoperability is enabled.

## Loading a Module

### Node.js — `js.require`

```jo
val path:  js.Dynamic = js.require("path")
val fetch: js.Dynamic = js.require("node-fetch")
```

`js.require` wraps Node.js's CommonJS `require()`. It works natively in Node.js and also in browser builds processed by a bundler (webpack, vite, rollup, parcel), which transforms `require` calls as part of the bundle step. It does **not** work in a browser without a bundler.

### Globals — `js.global`

For values that are already present on `globalThis` — browser APIs, Node.js globals, or anything injected by the host environment — use `js.global` instead of `js.require`:

```jo
// Node.js globals
val process:  js.Dynamic = js.global.process
val Buffer:   js.Dynamic = js.global.Buffer

// Browser globals
val document: js.Dynamic = js.global.document
val fetch:    js.Dynamic = js.global.fetch
val storage:  js.Dynamic = js.global.localStorage

// Universal (available everywhere)
val console:  js.Dynamic = js.global.console
val math:     js.Dynamic = js.global.Math
```

`js.global` is always safe to use in both environments. Prefer it over `js.require` when the value is guaranteed to exist on `globalThis`.

## Dynamic Member Access

`js.Dynamic` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | JavaScript equivalent | Underlying call              |
|-----------------|-----------------------|------------------------------|
| `x.foo`         | `x.foo`               | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`           | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`          | `callDynamic("foo", ...)`    |
| `x.call(...)`   | `x(...)`              | `call(...)`                  |
| `x[k]`          | `x[k]`                | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`            | `setDynamic(k, v)`           |

The JavaScript backend recognises these method calls on `js.Dynamic` and emits the corresponding JavaScript code.

The member name must be a **string literal** and a valid JavaScript identifier. This is enforced at compile time.

```jo
val math = js.global.Math

val pi:    Float = math.PI.asFloat          // property read
val root:  Float = math.sqrt(16.0).asFloat  // method call
val floor: Int   = math.floor(2.7).asInt
```

```jo
val process = js.global.process

process.env["MY_VAR"] = "hello"                  // item write
val v: String = process.env["MY_VAR"].asString   // item read
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets a `js.Dynamic` as a Jo type without any runtime conversion. The programmer asserts that the underlying JavaScript value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

### Convenience cast shortcuts

`js.Dynamic` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a JavaScript `string`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`js.Dynamic` implements `toString`, which calls JavaScript's `.toString()` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `js.Dynamic` works transparently in string concatenation and `println`:

```jo
val result: js.Dynamic = math.random()
println("random = " + result)   // calls .toString() automatically
println result                   // works directly
```

### Wrapping a Jo value

`js.dynamic(v)` converts any Jo value back to `js.Dynamic` for dynamic access. This is useful when you have a typed value but need to reach a method not in its interface:

```jo
val xs: js.Array = js.array(3, 1, 4, 1, 5)
js.dynamic(xs).sort()   // sort() not in js.Array, call dynamically via js.Dynamic
```

## Calling Conventions

JavaScript does not have keyword arguments. Optional parameters are passed positionally, or as a plain object (options bag). Use `js.obj` to build an options object:

```jo
val https = js.require("https")
val opts  = js.obj({"hostname": "example.com", "port": 443, "path": "/"})
https.get(opts, callback)
```

### Spreading an array as `...args`

When the arguments are known individually, pass them directly — no spreading needed:

```jo
val child_process: js.Dynamic = js.require("child_process")
child_process.execSync("ls", "--verbose", "--output", "out.txt")
```

When the arguments are held in a `js.Array` at runtime, use `js.spread` to expand the array as positional arguments:

```jo
def run(cmd: String, args: js.Array): Unit =
  child_process.execSync(cmd, js.spread(args))
```

## undefined and null Handling

JavaScript has two "nothing" values: `undefined` (unset or absent) and `null` (explicitly empty). Both are available as `js.undefined` and `js.null`. Test for them with the corresponding predicates:

```jo
val result: js.Dynamic = mapping["missing_key"]

if result.isUndefined then
  println "not found"
else
  println("found: " + result)
```

`isNullish` is a convenience that returns `true` for both `null` and `undefined`, matching JavaScript's `== null` check:

```jo
if result.isNullish then println "absent"
```

`===` is JavaScript strict equality, available as an infix operator on any `js.Dynamic`:

```jo
if result === js.undefined then ...   // identity check
if result === js.null then ...
if result === false then ...           // distinguishes false from other falsy values
```

`isInstance(cls)` maps to JavaScript's `instanceof`:

```jo
if js.isInstance(value, js.global.Map) then ...
```

`js.typeof(v)` returns the JavaScript `typeof` string:

```jo
if js.typeof(value) == "function" then ...
```

## Container Types

Jo provides typed interfaces for JavaScript's core container types.

### `js.Array` — mutable ordered sequence

```jo
val xs: js.Array = js.array(1, 2, 3)

xs.push(4)
xs[1] = 9

val first: js.Dynamic = xs[0]
val size:  Int      = xs.size
val has3:  Bool     = xs.includes(3)
val idx:   Int      = xs.indexOf(2)

val tail: js.Array = xs.slice(1)
val mid:  js.Array = xs.slice(1, 3)   // indices 1 up to (not including) 3
val str:  String   = xs.join(", ")

val popped: js.Dynamic = xs.pop()
```

Bracket syntax works directly on `js.Array` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: js.Dynamic = xs[0]
```

### Plain objects — `js.obj`

```jo
val d: js.Dynamic = js.obj({"x": 1, "y": 2})

d["z"] = 3
val byKey:  js.Dynamic = d["x"]     // bracket access
val byDot:  js.Dynamic = d.x        // dot access — same result
val hasY:   Bool     = js.hasOwn(d, "y")
```

For iteration over keys or values, use `js.global.Object`:

```jo
val keys:    js.Dynamic = js.global.Object.keys(d)
val values:  js.Dynamic = js.global.Object.values(d)
val entries: js.Dynamic = js.global.Object.entries(d)
```


## Exception Handling

`js.try` wraps an expression in a JavaScript `try/catch` block and returns `Ok(result)` on success or `Err(value)` on a thrown exception:

```jo
match js.try(js.require("optional-module"))
  case Ok(m)  => println "module available"
  case Err(e) => println("module not found: " + e)
```

The error value is `js.Dynamic` because JavaScript allows throwing any value, not only `Error` objects. For standard `Error` objects, access their properties via dynamic access:

```jo
match js.try(riskyCall())
  case Ok(v)  => println("ok: " + v)
  case Err(e) =>
    if e.isInstance(js.global.Error) then
      println("error: " + e.message.asString)
    else
      println("thrown: " + e)
```

`js.try` is intrinsified — the argument is **not** evaluated eagerly. The compiler wraps the call site in a `try/catch` block, so the expression itself is what's guarded.

## Constructor calls — `js.init`

Use `js.init` to call a JavaScript constructor with `new`:

```jo
val d: js.Dynamic = js.init(js.global.Date, 0)          // new Date(0)
val re: js.Dynamic = js.init(js.global.RegExp, "\\d+")  // new RegExp("\\d+")
val m: js.Dynamic = js.init(js.global.Map)               // new Map()
```

This is necessary because `Map()`, `Date()`, etc. are constructors that require `new` — calling them without `new` throws a `TypeError` in strict mode.

## Promises

Jo provides a thin `js.Promise` wrapper for the native JavaScript promise API.
It improves readability at the call site, but intentionally leaves callback
values as `Any` so you can cast only where needed.

```jo
val fs: js.Dynamic = js.require("fs").promises

fs.readFile("package.json", js.obj({"encoding": "utf-8"})).cast[js.Promise]
  .success((text: Any) =>
    println("bytes = " + js.dynamic(text).asString.size)
  )
  .catch((err: Any) =>
    println("read failed: " + js.dynamic(err).toString)
  )
  .finally(() =>
    println("done")
  )
```

The wrapper methods map directly to JavaScript:

```jo
promise.success(f)       // promise.then(f)
promise.andThen(ok, err) // promise.then(ok, err)
promise.catch(err)       // promise.catch(err)
promise.finally(f)       // promise.finally(f)
```

The `jo.js` namespace also provides thin helpers around `Promise` statics:

```jo
js.resolve(42)
js.reject("boom")
js.all(js.array(p1, p2, p3))
```

## Utility Functions

```jo
js.typeof(value)         // JavaScript typeof value  → Jo String
js.isUndefined(value)    // value === undefined       → Bool
js.isNull(value)         // value === null            → Bool
js.isNullish(value)      // value == null             → Bool
js.isInstance(obj, cls)  // obj instanceof cls        → Bool
js.hasOwn(obj, key)      // Object.hasOwn(obj, key)   → Bool
```

::: info
For anything not covered by the typed API — uncommon options, unusual calling conventions, or third-party libraries — drop down to the low-level mechanism: load the module with `js.require`, call methods dynamically via dot notation, and cast results as needed.

```jo
// Example: calling a function with an options object
val sharp = js.require("sharp")
val img: js.Dynamic = sharp.call("input.png")
img.resize(js.obj({"width": 320, "fit": "contain"}))
img.toFile("output.png")
```
:::

## Writing Typed Wrappers

Typed wrappers replace `js.Dynamic` with concrete Jo types at the boundary of a JavaScript module. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the JavaScript backend still resolves calls dynamically.

There are two complementary techniques for wrapping a JavaScript module.

### Interface cast — zero implementation

Declare a `@js.interop` interface that matches the module's shape, then cast the module directly to it. No method bodies are needed: the JavaScript backend resolves every call dynamically at runtime.

```jo
@js.interop
interface OsApi
  def platform(): String
  def arch():     String
  def hostname(): String
  def cpus():     js.Dynamic
end

def os: OsApi = js.require("os").cast[OsApi]
```

User code calls it like any Jo interface:

```jo
println(os.platform())   // e.g. "linux"
println(os.arch())       // e.g. "x64"
```

This technique works whenever the JavaScript method signatures map cleanly to Jo types. Use `js.Dynamic` as the return type for methods that return complex JavaScript objects you will inspect further.

### Wrapper annotations

The `@js.interop` annotation is required on all typed wrapper interfaces. It enables the wrapper annotations `@js.targetName` and `@js.property`, and activates the `..T` vararg calling convention described below.

**`@js.targetName("...")`.** Use this when the Jo-facing member name differs
from the JavaScript member name. This is especially useful when the JavaScript
name conflicts with Jo syntax:

```jo
@js.interop
interface PromiseLike
  @js.targetName("then")
  def success(onFulfilled: Any): PromiseLike
end
```

The backend lowers `success(...)` to a call to the JavaScript member `then(...)`.

**`@js.property`.** Use this on a parameterless wrapper member to force
JavaScript property access instead of a method call:

```jo
@js.interop
interface Stats
  @js.property
  def size: Int

  @js.targetName("isFile")
  def isFile: Bool

  @js.targetName("isDirectory")
  def isDir: Bool
end

section fs
  private def fsModule: js.Dynamic = js.require("fs")

  def stat(path: String): Stats =
    fsModule.statSync(path).cast[Stats]
end
```

User code then uses it as follows:

```jo
val s = fs.stat("package.json")
println(s.size)
println(s.isFile)
```

Without `@js.property`, the backend would attempt to call `size()` as a method.

`@js.property` and `@js.targetName` can be combined:

```jo
@js.interop
interface Entry
  @js.property
  @js.targetName("isDirectory")
  def isDir: Bool
end
```

This lowers `isDir` to a property read of `isDirectory`.

### Vararg arguments (`..T`)

Abstract methods in a `@js.interop` interface may declare vararg parameters using `..T`. The backend automatically spreads the arguments into the JavaScript call.

Node.js's `path.join(...parts)` is a typical example:

```jo
@js.interop
interface PathModule
  def join(parts: ..String): String
end

val path: PathModule = js.require("path").cast[PathModule]
println(path.join("usr", "local", "bin"))  // → "usr/local/bin"
```

Use `..xs` splice syntax to pass a `List[T]` as the vararg:

```jo
val segments: List[String] = List("usr", "local", "bin")
println(path.join(..segments))  // → "usr/local/bin"
```

### Concrete adapter methods

Some situations still require a concrete adapter body.

**Options-bag parameters.** When a JavaScript function expects an options object, how you handle it depends on how the wrapper is typed.

If the wrapper accepts the options as `js.Dynamic` or `Any`, the call-site is responsible for constructing the object. No concrete body is needed — the interface cast handles the call dynamically:

```jo
@js.interop
interface FS
  def readFile(path: String, options: js.Dynamic): String
end

val fs: FS = js.require("fs").cast[FS]

// Call site:
fs.readFile("data.txt", js.obj({"encoding": "utf-8"}))
```

If the wrapper exposes individual named parameters, a concrete body is required to build the options object. Other methods in the same interface that need no adaptation still have no body:

```jo
@js.interop
interface FS
  def exists(path: String): Bool

  def readFile(path: String, encoding: String = "utf-8"): String =
    js.dynamic(this).readFileSync(path, js.obj({"encoding": encoding})).asString

  def writeFile(path: String, data: String, encoding: String = "utf-8"): Unit =
    js.dynamic(this).writeFileSync(path, data, js.obj({"encoding": encoding}))
end

val fs: FS = js.require("fs").cast[FS]
```
