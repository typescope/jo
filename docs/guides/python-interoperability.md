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
val math: py.Value = py.importModule("math")
val os:   py.Value = py.importModule("os")
```

`py.importModule` is the entry point for any Python library. The returned `py.Value` gives access to all module-level attributes and functions.

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
val math = py.importModule("math")

val pi: Float  = math.pi.cast[Float]          // attribute read
val e:  Float  = math.e.cast[Float]

val root: Float = math.sqrt(16.0).cast[Float]  // method call
val floor: Int  = math.floor(2.7).cast[Int]
```

```jo
val os = py.importModule("os")

os.environ["MY_VAR"] = "hello"                 // item write
val v: String = os.environ["MY_VAR"].cast[String]  // item read
```

## Type Conversion

### Unsafe cast

`cast[T]` reinterprets a `py.Value` as a Jo type without any runtime conversion. The programmer asserts that the underlying Python value conforms to `T`. If the assertion is wrong, later operations on the result will fail at runtime.

```jo
val n: Int    = someValue.cast[Int]
val s: String = someValue.cast[String]
val b: Bool   = someValue.cast[Bool]
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

Use `py.kwarg("name", value)` to pass a keyword argument at the call site. The name must be a string literal.

```jo
val re = py.importModule("re")
val pat = re.compile("[a-z]+", py.kwarg("flags", re.IGNORECASE))
```

### Splicing a list as `*args`

Use `py.splice(list)` to expand a `py.List` as positional arguments:

```jo
val args = py.list("--verbose", "--output", "out.txt")
subprocess.check_output(py.splice(args))
```

### Spreading a dict as `**kwargs`

Use `py.kwargs(dict)` to expand a `py.Dict` as keyword arguments:

```jo
val opts = py.dict("encoding" ~ "utf-8", "errors" ~ "strict")
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
val collections = py.importModule("collections.abc")
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
match py.try(py.importModule("numpy"))
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
val it: py.Value = py.importModule("os").scandir(".")
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
val sys = py.importModule("sys")
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

## Writing Typed Wrappers

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

def platform: PlatformApi = py.importModule("platform").cast[PlatformApi]
```

User code calls it like any Jo interface:

```jo
println(platform.system())   // e.g. "Linux"
println(platform.machine())  // e.g. "x86_64"
```

This technique works whenever the Python method signatures map cleanly to Jo types. Use `py.Value` as the return type for methods that return complex Python objects you will inspect further.

### Concrete adapter methods

The interface cast works transparently for regular method calls, but two situations require a concrete method body.

**Attribute access.** Python attributes are not callable, so they must be read with `py.value(this).attr` rather than invoked as methods. Declare a concrete body using dot notation on `py.value(this)`:

```jo
private def subprocessModule: py.Value = py.importModule("subprocess")

interface CompletedProcess
  def returncode: Int    = py.value(this).returncode.cast[Int]
  def stdout:     String = py.value(this).stdout.cast[String]
  def stderr:     String = py.value(this).stderr.cast[String]
end

section subprocess
  def run(args: ..String): CompletedProcess =
    subprocessModule.run(
      py.list(args),
      py.kwarg("capture_output", true),
      py.kwarg("text", true)
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

Without the concrete bodies, the backend would attempt to call `returncode()` and `stdout()` as Python methods, which would raise `TypeError` at runtime.

**Keyword-only arguments.** When a Python method requires keyword arguments — either because they are keyword-only in Python, or because they are optional parameters you want to set by name — the default positional call does not work. Add a body using `py.kwarg`:

```jo
interface Path
  def read_text(): String                          // no kwargs needed

  def write_text(data: String): Int =              // encoding is keyword-only
    py.value(this).write_text(
      data,
      py.kwarg("encoding", "utf-8")
    ).cast[Int]

  def open(mode: String = "r"): py.Value =         // pass encoding as keyword
    py.value(this).open(mode, py.kwarg("encoding", "utf-8"))
end
```
