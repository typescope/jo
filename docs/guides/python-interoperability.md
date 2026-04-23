# Python Interoperability

Jo compiles to Python and provides a typed FFI layer for calling Python libraries from Jo code. This guide covers the full interoperability API.

::: warning
**Python FFI must be explicitly enabled.** The FFI API (`py.*`) is only available when the compiler flag `--use-runtime-api python` is passed at compile time. Without it, any reference to `py.*` will fail to resolve.

**Do not enable FFI when compiling untrusted code.** `--use-runtime-api python` gives compiled code unrestricted access to the Python runtime: arbitrary file system operations, network access, subprocess execution, and dynamic code evaluation. Only compile trusted source with this flag enabled.
:::

## Overview

The Python FFI is built around a single escape-hatch type, `py.Value`, which represents any Python object without a static type. From there, you progressively add type structure — either by casting to a Jo type, or by defining a typed wrapper interface.

All FFI primitives live in the `jo.py` namespace. As all names under `jo` are imported by default, users can directly use `py.XXX` without any importing when interoperability is enabled.

## Importing a Module

```jo
val math: py.Value = py.module("math")
val os:   py.Value = py.module("os")
```

`py.module` is the entry point for any Python library. The returned `py.Value` gives access to all module-level attributes and functions.

## Dynamic Member Access

`py.Value` resolves member accesses that are not statically known at the call site. The typer rewrites these transparently:

| Jo syntax       | Python equivalent   | Underlying call              |
|-----------------|---------------------|------------------------------|
| `x.foo`         | `x.foo`             | `selectDynamic("foo")`       |
| `x.foo = v`     | `x.foo = v`         | `updateDynamic("foo", v)`    |
| `x.foo(...)`    | `x.foo(...)`        | `callDynamic("foo", ...)`    |
| `x[k]`          | `x[k]`              | `getDynamic(k)`              |
| `x[k] = v`      | `x[k] = v`          | `setDynamic(k, v)`           |

The Python backend recognize the method calls `selectDynamic/callDynamic/etc` on
`py.Value` and issue the corresponding Python code.

The member name must be a **string literal** and a valid Python identifier. This is enforced at compile time.

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
val path: py.Value = py.call(pathlib.Path, "/tmp")

val types = py.module("types")
val box: py.Value = py.call(types.SimpleNamespace, value = 42)
```

`py.call(f, ...)` works for any Python callable object: functions, classes,
bound methods, lambdas, or instances with `__call__`.

It supports the same argument forms as other Python FFI calls:

```jo
val args = py.list("a", "b", "c")
val opts = py.dict("sep" ~ "-")

py.call(someCallable, py.splice(args), py.kwargs(opts))
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets a `py.Value` as a Jo type without any runtime conversion. The programmer asserts that the underlying Python value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.asInt
val s: String = someValue.asString
val b: Bool   = someValue.asBool
```

### Convenience cast shortcuts

`py.Value` provides shorthand methods for the four primitive types:

```jo
val i: Int    = v.asInt     // equivalent to v.cast[Int]
val f: Float  = v.asFloat   // equivalent to v.cast[Float]
val s: String = v.asString  // equivalent to v.cast[String]
val b: Bool   = v.asBool    // equivalent to v.cast[Bool]
```

Use `asString` when you know the value is already a Python `str`. Use `toString` (see below) when you want a human-readable representation of an arbitrary value.

### String conversion

`py.Value` implements `toString`, which calls Python's `str()` on the value. Because Jo uses `toString` as its standard string-conversion adapter, `py.Value` works transparently in string concatenation and `println`:

```jo
val result: py.Value = math.factorial(10)
println("10! = " + result)       // calls str() automatically
println result                    // works directly
```

### Wrapping a Jo value

`py.value(v)` converts any Jo value back to `py.Value` for dynamic access. This is useful when you have a typed value but need to call a method that isn't in its interface:

```jo
val lst: py.List = py.list(1, 2, 3)
py.value(lst).reverse()           // reverse() not in py.List, call dynamically
```

## Calling Conventions

### Keyword arguments

Use named argument syntax to pass Python keyword arguments at the call site:

```jo
val re = py.module("re")
val pat = re.compile("[a-z]+", flags = re.IGNORECASE)
```

Named arguments are forwarded to Python as keyword arguments. The name must be a valid Python identifier.

`py.kwarg("name", value)` is kept as an escape hatch for the rare case where the Python parameter name is also a Jo keyword (e.g. `end`, `type`, `class`) and cannot be written with named argument syntax directly:

```jo
py.print(value, py.kwarg("end", ""))   // "end" is a Jo keyword
```

### Splicing a list as `*args`

When the arguments are known individually, pass them directly — no splicing needed:

```jo
val subprocess: py.Value = py.module("subprocess")
subprocess.check_output("ls", "--verbose", "--output", "out.txt")
```

When the arguments are held in a `py.List` at runtime, use `py.splice` to expand the list as positional arguments:

```jo
def run(cmd: String, args: py.List): Unit =
  subprocess.check_output(cmd, py.splice(args))
```

### Spreading a dict as `**kwargs`

When the keyword arguments are known individually, pass them with named argument syntax directly — no dict needed:

```jo
f.open(encoding = "utf-8", errors = "strict")
```

When the keyword arguments are held in a `py.Dict` at runtime, use `py.kwargs` to expand the dict:

```jo
def openWith(opts: py.Dict): py.Value =
  f.open(py.kwargs(opts))
```

## None Handling

`py.none` is Python's `None` value. Test for it with `py.isNone` or the `isNone` method on `py.Value`:

```jo
val result: py.Value = mapping["missing_key"]

if result.isNone then
  println "not found"
else
  println("found: " + result)
```

`py.isSame(a, b)` maps to Python's `is` operator — identity comparison, not equality:

```jo
if py.isSame(result, py.none) then ...   // equivalent to result is None in Python
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

val first: py.Value = xs[0]
val size:  Int      = xs.size
val has3:  Bool     = xs.contains(3)

xs.appendAll(py.list(5, 6))
val popped: py.Value = xs.pop()         // removes last element
val at1:    py.Value = xs.pop(1)        // removes element at index 1
xs.clear()
```

Bracket syntax works directly on `py.List` via the `get`/`set` bridge:

```jo
xs[0] = 42
val v: py.Value = xs[0]
```

### `py.Tuple` — immutable sequence

```jo
val t: py.Tuple = py.tuple("a", "b", "c")

val first: py.Value = t[0]
val size:  Int      = t.size
```

### `py.Dict` — mutable mapping

```jo
val d: py.Dict = py.dict("x" ~ 1, "y" ~ 2)

d["z"] = 3
val v:    py.Value = d["x"]
val miss: py.Value = d.get("missing", "default")  // with fallback

val hasY: Bool = d.contains("y")
val size: Int  = d.size

val keys:   py.Value = d.keys()
val values: py.Value = d.values()
val items:  py.Value = d.items()

d.update(py.dict("a" ~ 1))
val copy: py.Dict = d.copy()
val rm:   py.Value = d.pop("x")
d.clear()
```

## Exception Handling

`py.try` wraps an expression in a Python `try/except` block and returns `Ok(result)` on success or `Err(error)` on a Python exception:

```jo
match py.try(py.module("numpy"))
  case Ok(np)  => println "numpy available"
  case Err(e)  => println("numpy not found: " + e.message)
```

`e.message` calls Python's `str()` on the exception, which gives the standard error message.

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

`py.next(it)` is the equivalent top-level form, useful when working with a raw `py.Value` iterator:

```jo
val it: py.Value = py.module("os").scandir(".")
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
while !py.isSame(item, sentinel) do ...
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

Typed wrappers replace `py.Value` with concrete Jo types at the boundary of a Python module. This gives callers static type checking, IDE completion, and self-documenting APIs — without any runtime overhead, since the Python backend still resolves calls dynamically.

There are two complementary techniques for wrapping a Python module.

### Interface cast — zero implementation

Declare an interface that matches the Python object's shape, then cast the module directly to it. No method bodies are needed: the Python backend resolves every call dynamically at runtime.

```jo
// file: platform.jo
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

**Keyword-only arguments.** For parameters that are keyword-only in Python (defined after `*` in the Python signature), annotate them with `py.Keyword[T]`. The backend then forwards the argument as a keyword argument regardless of whether the caller uses named or positional syntax — no concrete body needed:

```jo
interface Path
  def read_text(): String
  def write_text(data: String, encoding: py.Keyword[Any]): Int
end

// Both call styles work correctly:
path.write_text(data, encoding = "utf-8")   // named  → write_text(data, encoding="utf-8")
path.write_text(data, "utf-8")              // positional → write_text(data, encoding="utf-8")
```

When the keyword-only parameter has a sensible default, declare it on the Jo side. The backend forwards the synthesized default as a keyword argument too:

```jo
interface BuiltinsApi
  def sorted(iterable: Any, reverse: py.Keyword[Bool] = false): py.Value
end

builtins.sorted(lst)                  // emits: sorted(lst, reverse=False)
builtins.sorted(lst, reverse = true)  // emits: sorted(lst, reverse=True)
```

**Positional-only parameters.** Some Python methods reject keyword arguments entirely (e.g. `list.pop()`). Annotate such parameters with `py.Positional[T]` so the Python backend strips any named-argument key and always forwards the value positionally:

```jo
interface MyList
  def pop(i: py.Positional[Int] = -1): py.Value   // emits: lst.pop(-1), never lst.pop(i=-1)
end
```

### Wrapper annotations

For typed wrappers, the Python backend provides two annotations to cover the most common naming mismatches without requiring a handwritten adapter body.

**`@py.targetName("...")`.** Use this when the Jo-facing member name differs from the Python member name. This is especially useful when the Python name is a Jo keyword:

```jo
interface PromiseLike
  @py.targetName("then")
  def andThen(onFulfilled: Any): PromiseLike
end
```

The backend lowers `andThen(...)` to a call to the Python member `then(...)`.

**`@py.property`.** Use this on a parameterless wrapper member to force Python attribute access instead of a method call:

```jo
private def subprocessModule: py.Value = py.module("subprocess")

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
interface Path
  @py.property
  @py.targetName("parent")
  def parentPath: Path
end
```

This lowers `parentPath` to an attribute read of `parent`.

Property setters are intentionally unsupported. If a Python API requires attribute writes, use `py.Value` explicitly so the mutation stays visible at the interop boundary.

### Concrete adapter methods

The interface cast covers most cases, but some situations still require a concrete method body.

**Vararg arguments.** Jo varargs (`..Any`) are not automatically spliced into Python `*args`. Convert them to a Python list first, then use `py.splice` to pass the list as `*args`:

```jo
interface Path
  def joinpath(segments: ..Any): Path =
    val l = py.list(..segments)       // expand Jo varargs into a Python list
    py.value(this).joinpath(py.splice(l)).cast[Path]  // pass as Python *args
end
```

**Keyword-only parameter whose name is a Jo keyword.** If the Python parameter name is also a Jo keyword (`end`, `type`, `class`, …), named argument syntax cannot be written at the call site. A concrete adapter body with `py.kwarg` is still required:

```jo
interface TextIOWrapper
  // "end" is a Jo keyword — rename it on the Jo side and bridge with py.kwarg.
  def writeLine(value: Any, suffix: String = "\n"): Unit =
    py.value(this).write(value, py.kwarg("end", suffix))
end
```
