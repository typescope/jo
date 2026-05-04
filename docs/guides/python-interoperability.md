# Python Interoperability

Jo compiles to Python and provides a typed FFI layer for calling Python libraries from Jo code. This guide covers the full interoperability API.

::: warning
**Python FFI must be explicitly enabled.** The FFI API (`py.*`) is only available when the compiler flag `--use-runtime-api python` is passed at compile time. Without it, any reference to `py.*` will fail to resolve.

**Do not enable FFI when compiling untrusted code.** `--use-runtime-api python` gives compiled code unrestricted access to the Python runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.
:::

## Overview

The Python FFI is built around a single escape-hatch type, `py.Dynamic`, which represents any Python object without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.py` namespace. As all names under `jo` are imported by default, users can directly use `py.XXX` without any importing when interoperability is enabled.

## Importing a Module

```jo
val math: py.Dynamic = py.module("math")
val os:   py.Dynamic = py.module("os")
```

`py.module` is the entry point for any Python library. The returned `py.Dynamic` gives access to all module-level attributes and functions.

## Dynamic Member Access

`py.Dynamic` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | Python equivalent   | Underlying call              |
|-----------------|---------------------|------------------------------|
| `x.foo`         | `x.foo`             | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`         | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`        | `callDynamic("foo", ...)`    |
| `x[k]`          | `x[k]`              | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`          | `setDynamic(k, v)`           |

The Python backend recognize the method calls `selectDynamic/callDynamic/etc` on
`py.Dynamic` and issue the corresponding Python code.

The member name must be a **string literal** and a valid Python member identifier. This is enforced at compile time.

```jo
val math = py.module("math")

val pi: Float  = math.pi.asFloat          // attribute read
val e:  Float  = math.e.asFloat

val root: Float = math.sqrt(16.0).asFloat  // method call
val floor: Int  = math.floor(2.7).asInt
```

```jo
val os = py.module("os")

os.environ["MY_VAR"] = "hello"                 // item write
val v: String = os.environ["MY_VAR"].asString  // item read
```

## Calling Callable Objects

Use `py.call` when you already have a Python callable value and want to emit
Python call syntax directly:

```jo
val pathlib = py.module("pathlib")
val path: py.Dynamic = py.call(pathlib.Path, "/tmp")

val types = py.module("types")
val box: py.Dynamic = py.call(types.SimpleNamespace, value = 42)
```

`py.call(f, ...)` works for any Python callable object: functions, classes,
bound methods, lambdas, or instances with `__call__`.

It supports the same argument forms as other Python FFI calls:

```jo
val items: List[Any] = List("a", "b", "c")

py.call(someCallable, ..items, sep = "-")
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets a `py.Dynamic` as a Jo type without any runtime conversion. The programmer asserts that the underlying Python value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.asInt
val s: String = someValue.asString
val b: Bool   = someValue.asBool
```

### Convenience cast shortcuts

`py.Dynamic` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a Python `str`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`py.Dynamic` implements `toString`, which calls Python's `str()` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `py.Dynamic` works transparently in string concatenation and `println`:

```jo
val result: py.Dynamic = math.factorial(10)
println("10! = " + result)       // calls str() automatically
println result                    // works directly
```

### Wrapping a Jo value

`py.dynamic(v)` converts any Jo value back to `py.Dynamic` for dynamic access. This is useful when you have a typed value but need to call a method that isn't in its interface:

```jo
val lst: py.List = py.list(1, 2, 3)
py.dynamic(lst).reverse()           // reverse() not in py.List, call dynamically
```

## Calling Conventions

### Keyword arguments

Use named argument syntax to pass Python keyword arguments at the call site:

```jo
val re = py.module("re")
val pat = re.compile("[a-z]+", flags = re.IGNORECASE)
```

Named arguments are forwarded to Python as keyword arguments. The name must be a valid Python identifier.

When the Python parameter name is a Jo keyword (e.g. `end`, `type`, `class`) and cannot be written with named argument syntax, use `namedArg` from `jo.compile`:

```jo
import jo.compile.namedArg

py.dynamic(obj).write(value, namedArg("end", ""))
```

For typed wrappers, use `@py.keyword("rename")` on the parameter type instead — no adapter body needed.

### Splicing a list as `*args`

When the arguments are known individually, pass them directly — no splicing needed:

```jo
val subprocess: py.Dynamic = py.module("subprocess")
subprocess.check_output("ls", "--verbose", "--output", "out.txt")
```

When the arguments are held in a list at runtime, use Jo's native splice syntax `..xs` to expand them as positional arguments:

```jo
def run(cmd: String, args: List[String]): Unit =
  subprocess.check_output(cmd, ..args)
```

### Keyword arguments

Pass keyword arguments directly with named argument syntax:

```jo
f.open(encoding = "utf-8", errors = "strict")
```

`**dict` spreading is intentionally not supported. It is not needed in typed interop: when calling a known Python API, the argument names are always known at the call site, so named argument syntax covers most use cases. Spreading an opaque runtime dict would undermine the interop contract.

## None Handling

`py.none` is Python's `None` value. Test for it with `py.isNone` or the `isNone` method on `py.Dynamic`:

```jo
val result: py.Dynamic = mapping["missing_key"]

if result.isNone then
  println "not found"
else
  println("found: " + result)
```

`py.isIdentical(a, b)` maps to Python's `is` operator — identity comparison, not equality:

```jo
if py.isIdentical(result, py.none) then ...   // equivalent to result is None in Python
```

`py.isInstance(obj, cls)` maps to Python's `isinstance`:

```jo
val collections = py.module("collections.abc")
if py.isInstance(value, collections.Mapping) then ...
```

## Container Types

Jo provides typed interfaces for Python's three core container types.

### `py.List` — mutable sequence

```jo
val xs: py.List = py.list(1, 2, 3)

xs.append(4)
xs.insert(0, 0)
xs[1] = 9

val first: py.Dynamic = xs[0]
val size:  Int      = xs.size
val has3:  Bool     = xs.contains(3)

xs.appendAll(py.list(5, 6))
val popped: py.Dynamic = xs.pop()         // removes last element
val at1:    py.Dynamic = xs.pop(1)        // removes element at index 1
xs.clear()
```

Bracket syntax works directly on `py.List` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: py.Dynamic = xs[0]
```

### `py.Tuple` — immutable sequence

```jo
val t: py.Tuple = py.tuple("a", "b", "c")

val first: py.Dynamic = t[0]
val size:  Int      = t.size
```

### `py.Dict` — mutable mapping

```jo
val d: py.Dict = py.dict("x" ~ 1, "y" ~ 2)

d["z"] = 3
val v:    py.Dynamic = d["x"]
val miss: py.Dynamic = d.get("missing", "default")  // with fallback

val hasY: Bool = d.contains("y")
val size: Int  = d.size

val keys:   py.Dynamic = d.keys()
val values: py.Dynamic = d.values()
val items:  py.Dynamic = d.items()

d.update(py.dict("a" ~ 1))
val copy: py.Dict = d.copy()
val rm:   py.Dynamic = d.pop("x")
d.clear()
```

## Exception Handling

`py.try` wraps an expression in a Python `try/except` block and returns `Ok(result)` on success or `Err(error)` on a Python exception:

```jo
match py.try(py.module("numpy"))
  case Ok(np)  => println "numpy available"
  case Err(e)  => println("numpy not found: " + e.message)
```

`py.Error` exposes three fields:

| Field | Description |
|-------|-------------|
| `e.message` | Exception message string (`str(e)`) |
| `e.typeName` | Python exception class name, e.g. `"ValueError"` |
| `e.traceback` | Full formatted traceback (equivalent to `traceback.format_exception`) |

```jo
match py.try(py.module("json").loads("[bad"))
  case Ok(v)  => println(v)
  case Err(e) =>
    println("type: " + e.typeName)
    println("msg:  " + e.message)
    println(e.traceback)
```

`py.try` is intrinsified — the argument is **not** evaluated eagerly. The compiler wraps the call site in a `try/except` block, so the expression itself is what's guarded.

## Iteration

Use `py.iter(obj)` to obtain a typed iterator. Call `.next()` to advance it — `py.none` is the default sentinel, so `item.isNone` detects exhaustion:

```jo
val it: py.Iter = py.iter(collection)
var item = it.next()
while !item.isNone do
  // ... use item ...
  item = it.next()
end
```

`py.next(it)` is the equivalent top-level form, useful when working with a raw `py.Dynamic` iterator:

```jo
val it: py.Dynamic = py.module("os").scandir(".")
var entry = py.next(it)
while !entry.isNone do
  println(entry.name)
  entry = py.next(it)
end
```

Both default to `py.none` as the sentinel. Pass an explicit value when `None` could be a legitimate item in the sequence:

```jo
val sentinel = py.list()
var item = it.next(sentinel)
while !py.isIdentical(item, sentinel) do ...
```

## File I/O

`py.open` wraps Python's built-in `open`. The `mode` defaults to `"r"` and `encoding` defaults to Python's system encoding:

```jo
// Writing
val f = py.open("output.txt", "w")
f.write("hello\n")
f.close()

// Reading with system encoding
val f = py.open("data.txt")
val content: String = f.read().asString
f.close()

// Reading with explicit encoding
val f = py.open("data.txt", "r", "utf-8")
val content: String = f.read().asString
f.close()
```

## Debugging

`py.print` bypasses Jo's `stdout` capability and writes directly to a Python file handle. This makes it useful for debug output that should always appear regardless of the capability wiring:

```jo
val sys = py.module("sys")
py.print("debug: value = " + result, file = sys.stderr)
```

`py.input` reads a line from stdin:

```jo
val line: String = py.input()
```

## Utility Functions

```jo
py.str(value)           // Python str(value) → Jo String
py.len(obj)             // Python len(obj)   → Jo Int
py.contains(obj, value) // Python operator.contains(obj, value) → Bool
```

::: info
For anything not covered by the typed API — uncommon parameters, rarely-used builtins, or third-party libraries with non-standard conventions — drop down to the low-level mechanism: get the module with `py.module`, call methods dynamically via dot notation and named argument syntax, and cast results as needed.

```jo
// Example: calling open() with parameters not exposed by py.open
val f = py.module("builtins").open(
  "data.txt",
  mode = "r",
  encoding = "latin-1",
  errors = "replace",
  newline = "")
val content: String = f.read().asString
f.close()
```
:::

## Writing Typed Wrappers

Typed wrappers replace `py.Dynamic` with concrete Jo types at the boundary of a Python module. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the Python backend still resolves calls dynamically.

**`@py.interop` is required on every typed wrapper interface.** It tells the Python backend to resolve all abstract method calls dynamically and enables the vararg conventions described below. Without it, `@py.property` and `@py.targetName` are rejected and vararg parameters are not unpacked.

There are two complementary techniques for wrapping a Python module.

### Interface cast — zero implementation

Declare an interface that matches the Python object's shape, then cast the module directly to it. No method bodies are needed: the Python backend resolves every call dynamically at runtime.

```jo
// file: platform.jo
@py.interop
interface PlatformApi
  def system(): String
  def machine(): String
  def node(): String
  def python_version(): String
end

def platform: PlatformApi = py.module("platform").cast[PlatformApi]
```

User code calls it like any Jo interface:

```jo
println(platform.system())   // e.g. "Linux"
println(platform.machine())  // e.g. "x86_64"
```

**Keyword-only arguments.** For parameters that are keyword-only in Python (defined after `*` in the Python signature), annotate the parameter type with `@py.keyword`. The backend then forwards the argument as a keyword argument regardless of whether the caller uses named or positional syntax — no concrete body needed:

```jo
@py.interop
interface Path
  def read_text(): String
  def write_text(data: String, encoding: Any @py.keyword): Int
end

// Both call styles work correctly:
path.write_text(data, encoding = "utf-8")   // named      → write_text(data, encoding="utf-8")
path.write_text(data, "utf-8")              // positional → write_text(data, encoding="utf-8")
```

When the keyword-only parameter has a sensible default, declare it on the Jo side. The backend forwards the synthesized default as a keyword argument too:

```jo
@py.interop
interface BuiltinsApi
  def sorted(iterable: Any, reverse: Bool @py.keyword = false): py.Dynamic
end

builtins.sorted(lst)                  // emits: sorted(lst, reverse=False)
builtins.sorted(lst, reverse = true)  // emits: sorted(lst, reverse=True)
```

**Renaming keyword arguments.** When the Python parameter name is also a Jo keyword (e.g. `end`, `type`, `class`), it cannot be used as the Jo parameter name directly. Pass the Python name to `@py.keyword` and give the parameter a valid Jo name:

```jo
@py.interop
interface TextIOWrapper
  def writeLine(value: Any, suffix: String @py.keyword("end") = "\n"): Unit
end

wrapper.writeLine("hello")             // emits: wrapper.writeLine("hello", end="\n")
wrapper.writeLine("hello", suffix = "") // emits: wrapper.writeLine("hello", end="")
```

**Positional-only parameters.** Some Python methods reject keyword arguments entirely (e.g. `list.pop()`). Annotate the parameter type with `@py.positional` so the Python backend strips any named-argument key and always forwards the value positionally:

```jo
@py.interop
interface MyList
  def pop(i: Int @py.positional = -1): py.Dynamic   // emits: lst.pop(-1), never lst.pop(i=-1)
end
```

### Wrapper annotations

For typed wrappers, the Python backend provides two annotations to cover the most common naming mismatches without requiring a handwritten adapter body.

**`@py.targetName("...")`.** Use this when the Jo-facing member name differs from the Python member name. This is especially useful when the Python name is a Jo keyword:

```jo
@py.interop
interface PromiseLike
  @py.targetName("then")
  def andThen(onFulfilled: Any): PromiseLike
end
```

The backend lowers `andThen(...)` to a call to the Python member `then(...)`.

**`@py.property`.** Use this on a parameterless wrapper member to force Python attribute access instead of a method call:

```jo
private def subprocessModule: py.Dynamic = py.module("subprocess")

@py.interop
interface CompletedProcess
  @py.property
  def returncode: Int

  @py.property
  def stdout: String

  @py.property
  def stderr: String
end

section subprocess
  def run(args: ..String): CompletedProcess =
    subprocessModule.run(
      py.list(args),
      capture_output = true,
      text = true
    ).cast[CompletedProcess]
end
```

User code then uses it as follows:

```
// Usage:
val proc = subprocess.run(py.list("git", "log", "--oneline"))
println(proc.stdout)
println(proc.returncode)
```

Without `@py.property`, the backend would attempt to call `returncode()` and `stdout()` as Python methods, which would raise `TypeError` at runtime.

`@py.property` and `@py.targetName` can be combined:

```jo
@py.interop
interface Path
  @py.property
  @py.targetName("parent")
  def parentPath: Path
end
```

This lowers `parentPath` to an attribute read of `parent`.

Property setters are intentionally unsupported. If a Python API requires attribute writes, use `py.Dynamic` explicitly so the mutation stays visible at the interop boundary.

### Vararg arguments

`@py.interop` interfaces support three calling conventions for vararg parameters. The convention is chosen by the element type of the vararg:

| Vararg type | Positional args | Splice `..xs` | Keyword `k=v` |
|-------------|:-----------:|:-----------:|:-----------:|
| `..T` | ✓ | ✓ | ✗ |
| `..Mixed[T]` | ✓ | ✓ | ✓ |
| `..Named[T]` | ✗ | ✗ | ✓ |

**`..T` — positional varargs.** Use when the Python function accepts only positional `*args`. Individual values and splices are both supported:

```jo
@py.interop
interface Path
  def joinpath(segments: ..String): Path
end

path.joinpath("a", "b", "c")    // emits: path.joinpath("a", "b", "c")
path.joinpath(..segs)            // emits: path.joinpath(*segs)
```

**`..Mixed[T]` — positional, splice, and keyword.** Use when the Python function accepts both `*args` and `**kwargs`. Import `jo.compile.Mixed` to use this type:

```jo
import jo.compile.Mixed

@py.interop
interface BuiltinsMod
  def print(args: ..Mixed[Any]): Unit
end

bm.print("hello", "world", sep = "|")  // emits: print("hello", "world", sep="|")
bm.print(..words)                       // emits: print(*words)
```

**`..Named[T]` — keyword-only.** Use when the Python function accepts only `**kwargs`. Import `jo.compile.Named` to use this type:

```jo
import jo.compile.Named

@py.interop
interface BuiltinsDict
  def dict(opts: ..Named[Any]): py.Dynamic
end

bd.dict(x = 1, y = 2)    // emits: dict(x=1, y=2)
```
