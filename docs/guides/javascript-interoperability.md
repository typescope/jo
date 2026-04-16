# JavaScript Interoperability

Jo compiles to JavaScript and provides a typed FFI layer for calling JavaScript libraries from Jo code. This guide covers the full interoperability API.

::: warning
**JavaScript FFI must be explicitly enabled.** The FFI API (`js.*`) is only available when the compiler flag `--use-runtime-api javascript` is passed at compile time. Without it, any reference to `js.*` will fail to resolve.

**Do not enable FFI when compiling untrusted code.** `--use-runtime-api javascript` gives compiled code unrestricted access to the JavaScript runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.
:::

## Overview

The JavaScript FFI is built around a single escape-hatch type, `js.Value`, which represents any JavaScript value without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.js` namespace. As all names under `jo` are imported by default, users can directly use `js.XXX` without any importing when interoperability is enabled.

## Loading a Module

### Node.js — `js.require`

```jo
val path:  js.Value = js.require("path")
val fetch: js.Value = js.require("node-fetch")
```

`js.require` wraps Node.js's CommonJS `require()`. It works natively in Node.js and also in browser builds processed by a bundler (webpack, vite, rollup, parcel), which transforms `require` calls as part of the bundle step. It does **not** work in a browser without a bundler.

### Globals — `js.global`

For values that are already present on `globalThis` — browser APIs, Node.js globals, or anything injected by the host environment — use `js.global` instead of `js.require`:

```jo
// Node.js globals
val process:  js.Value = js.global.process
val Buffer:   js.Value = js.global.Buffer

// Browser globals
val document: js.Value = js.global.document
val fetch:    js.Value = js.global.fetch
val storage:  js.Value = js.global.localStorage

// Universal (available everywhere)
val console:  js.Value = js.global.console
val math:     js.Value = js.global.Math
```

`js.global` is always safe to use in both environments. Prefer it over `js.require` when the value is guaranteed to exist on `globalThis`.

## Dynamic Member Access

`js.Value` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | JavaScript equivalent | Underlying call              |
|-----------------|-----------------------|------------------------------|
| `x.foo`         | `x.foo`               | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`           | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`          | `callDynamic("foo", ...)`    |
| `x.call(...)`   | `x(...)`              | `call(...)`                  |
| `x[k]`          | `x[k]`                | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`            | `setDynamic(k, v)`           |

The JavaScript backend recognises these method calls on `js.Value` and emits the corresponding JavaScript code.

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

`cast[T]` reinterprets a `js.Value` as a Jo type without any runtime conversion. The programmer asserts that the underlying JavaScript value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

### Convenience cast shortcuts

`js.Value` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a JavaScript `string`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`js.Value` implements `toString`, which calls JavaScript's `.toString()` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `js.Value` works transparently in string concatenation and `println`:

```jo
val result: js.Value = math.random()
println("random = " + result)   // calls String() automatically
println result                   // works directly
```

### Wrapping a Jo value

`js.value(v)` converts any Jo value back to `js.Value` for dynamic access. This is useful when you have a typed value but need to reach a method not in its interface:

```jo
val xs: js.Array = js.array(3, 1, 4, 1, 5)
js.value(xs).callDynamic("sort")   // sort() not in js.Array, call dynamically
```

## Calling Conventions

JavaScript does not have keyword arguments. Optional parameters are passed positionally, or as a plain object (options bag). Use `js.obj` to build an options object:

```jo
val https = js.require("https")
val opts  = js.obj({"hostname": "example.com", "port": 443, "path": "/"})
https.get(opts, callback)
```

### Spreading an array as `...args`

Use `js.spread(xs)` to expand a `js.Array` as positional arguments:

```jo
val args: js.Array = js.array("--verbose", "--output", "out.txt")
child_process.execSync(js.spread(args))
```

## undefined and null Handling

JavaScript has two "nothing" values: `undefined` (unset or absent) and `null` (explicitly empty). Both are available as `js.undefined` and `js.null`. Test for them with the corresponding predicates:

```jo
val result: js.Value = mapping["missing_key"]

if result.isUndefined then
  println "not found"
else
  println("found: " + result)
```

`isNullish` is a convenience that returns `true` for both `null` and `undefined`, matching JavaScript's `== null` check:

```jo
if result.isNullish then println "absent"
```

`===` is JavaScript strict equality, available as an infix operator on any `js.Value`:

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

Jo provides typed interfaces for JavaScript's four core container types.

### `js.Array` — mutable ordered sequence

```jo
val xs: js.Array = js.array(1, 2, 3)

xs.push(4)
xs[1] = 9

val first: js.Value = xs[0]
val size:  Int      = xs.size
val has3:  Bool     = xs.includes(3)
val idx:   Int      = xs.indexOf(2)

val tail: js.Array = xs.slice(1)
val mid:  js.Array = xs.slice(1, 3)   // indices 1 up to (not including) 3
val str:  String   = xs.join(", ")

val popped: js.Value = xs.pop()
```

Bracket syntax works directly on `js.Array` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: js.Value = xs[0]
```

### Plain objects — `js.obj`

```jo
val d: js.Value = js.obj({"x": 1, "y": 2})

d["z"] = 3
val v:    js.Value = d["x"]
val hasY: Bool     = js.hasOwn(d, "y")
```

For iteration over keys or values, use `js.global.Object`:

```jo
val keys:    js.Value = js.global.Object.keys(d)
val values:  js.Value = js.global.Object.values(d)
val entries: js.Value = js.global.Object.entries(d)
```


## Exception Handling

`js.try` wraps an expression in a JavaScript `try/catch` block and returns `Ok(result)` on success or `Err(error)` on a thrown exception:

```jo
match js.try(js.require("optional-module"))
  case Ok(m)  => println "module available"
  case Err(e) => println("module not found: " + e.message)
```

`e.message`, `e.name`, and `e.stack` give the standard `Error` properties. If the thrown value is not an `Error` object, `message` falls back to its string representation.

`js.try` is intrinsified — the argument is **not** evaluated eagerly. The compiler wraps the call site in a `try/catch` block, so the expression itself is what's guarded.

## Iteration

Most JavaScript APIs return plain arrays, which `js.Array` covers directly. When you encounter a non-array iterable (such as the return value of `Map.keys()` or a generator), convert it to an array with `Array.from` first:

```jo
val arr: js.Array = js.global.Array.from(iterable).cast[js.Array]
```

This gives you indexed access and all `js.Array` methods.

## JSON

Access the global `JSON` object via `js.global`:

```jo
val JSON = js.global.JSON

// Parsing
val data: js.Value = JSON.parse("{\"x\": 1, \"y\": 2}")
val x: Int = data.x.asInt

// Compact serialization
val s: String = JSON.stringify(data).asString

// Pretty-printed with 2-space indent
val pretty: String = JSON.stringify(data, js.null, 2).asString
```

## Utility Functions

```jo
js.str(value)            // JavaScript String(value) → Jo String
js.typeof(value)         // JavaScript typeof value  → Jo String
js.isUndefined(value)    // value === undefined       → Bool
js.isNull(value)         // value === null            → Bool
js.isNullish(value)      // value == null             → Bool
js.hasOwn(obj, key)      // Object.hasOwn(obj, key)   → Bool
```

::: info
For anything not covered by the typed API — uncommon options, unusual calling conventions, or third-party libraries — drop down to the low-level mechanism: load the module with `js.require`, call methods dynamically via dot notation, and cast results as needed.

```jo
// Example: calling a function with an options object
val sharp = js.require("sharp")
val img: js.Value = sharp.call("input.png")
img.resize(js.obj({"width": 320, "fit": "contain"}))
img.toFile("output.png")
```
:::

## Writing Typed Wrappers

There are two complementary techniques for wrapping a JavaScript module.

### Interface cast — zero implementation

Declare an interface that matches the module's shape, then cast the module directly to it. No method bodies are needed: the JavaScript backend resolves every call dynamically at runtime.

```jo
interface OsApi
  def platform(): String
  def arch():     String
  def hostname(): String
  def cpus():     js.Value
end

def os: OsApi = js.require("os").cast[OsApi]
```

User code calls it like any Jo interface:

```jo
println(os.platform())   // e.g. "linux"
println(os.arch())       // e.g. "x64"
```

This technique works whenever the JavaScript method signatures map cleanly to Jo types. Use `js.Value` as the return type for methods that return complex JavaScript objects you will inspect further.

### Concrete adapter methods

The interface cast works for regular method calls, but three situations require a concrete method body.

**Property access.** JavaScript properties are not callable, so they must be read with `js.value(this).prop` rather than invoked as methods. Declare a concrete body using dot notation on `js.value(this)`:

```jo
interface Stats
  def size:    Int    = js.value(this).size.asInt
  def isFile:  Bool   = js.value(this).isFile().asBool
  def isDir:   Bool   = js.value(this).callDynamic("isDirectory").asBool
end

section fs
  private def fsModule: js.Value = js.require("fs")

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

Without the concrete bodies, the backend would attempt to call `size()` and `isFile()` as JavaScript methods, which would raise a runtime error.

**Vararg arguments.** Jo varargs (`..Any`) are not automatically spread into JavaScript `...args`. Convert them to a `js.Array` first, then use `js.spread`:

```jo
interface Path
  def join(segments: ..String): String =
    val arr = js.array(..segments)
    js.require("path").join(js.spread(arr)).asString
end
```

**Options-bag parameters.** When a JavaScript function expects an options object for optional parameters, build it with `js.obj`:

```jo
section fs
  def readFile(path: String, encoding: String = "utf-8"): String =
    fsModule.readFileSync(path, js.obj({"encoding": encoding})).asString

  def writeFile(path: String, data: String, encoding: String = "utf-8"): Unit =
    fsModule.writeFileSync(path, data, js.obj({"encoding": encoding}))
end
```

### Wrapping ES6 Map and Set

`js.Map` and `js.Set` are not part of the core API — they appear rarely in real JavaScript APIs. When you need them, define a typed wrapper:

```jo
interface JsMap
  def get(k: Any): js.Value
  def set(k: Any, v: Any): Unit
  def has(k: Any): Bool
  def size: Int         = js.value(this).size.asInt
  def remove(k: Any): Bool = js.value(this).callDynamic("delete", k).asBool
  def clear(): Unit
  def keys():    js.Value
  def values():  js.Value
  def entries(): js.Value
end

def jsMap(): JsMap = js.global.Map().cast[JsMap]
```

```jo
interface JsSet
  def add(v: Any): Unit
  def has(v: Any): Bool
  def size: Int        = js.value(this).size.asInt
  def remove(v: Any): Bool = js.value(this).callDynamic("delete", v).asBool
  def clear(): Unit
  def values(): js.Value
end

def jsSet(): JsSet = js.global.Set().cast[JsSet]
```
