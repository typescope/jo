# Lowering Peak Memory in Native Programs

Experimental. This guide covers the LLVM backend (`jo compile --llvm`).

A native program frees memory in regions. By default every function that can
allocate runs in a region of its own. When the call returns, the region frees
what the call allocated, except what it returned or stored into an older
object. You need no annotations and no options for this.

Regions only change peak memory, the resident set size (RSS), never what a
program computes. When a program uses more memory than you expect, the runtime
tells you where it goes, and a small change to the program usually fixes it.

## The loop

1. Run the program with `JO_MEMSTATS=1`. It prints a report on stderr when it
   exits.
2. Read the `summary` and `to-runtime` lines. If `runtime-region` is a large
   share of `allocated`, or `hint main` appears, the runtime region is the
   problem. If `runtime-region` is small but `peak-rss-kb` is still high, look
   at the `region` rows.
3. Rebuild with `--mem-profile` and run again. The report then names the
   functions responsible.
4. Take the largest row and apply the fix its hint names.
5. Run again and compare `peak-rss-kb`.

```sh
jo compile --llvm --mem-profile app.jo -o app
JO_MEMSTATS=1 ./app
```

Stop when the remaining rows are data the program keeps, such as the
entries of a map it needs. Further changes then trade correctness or
speed for little memory.

The report starts with these lines. `growth` lines (see
[Long-running programs](#long-running-programs)) can come before them.

```
jo-mem summary peak-rss-kb=56704 allocated=1000087 freed=72 runtime-region=1000015 skipped-enters=0
jo-mem to-runtime direct=1000015 store=0 trace=0 return=0
jo-mem closes free=2 merge=0 collect=0
```

- `peak-rss-kb` is the peak RSS. This is the number to lower.
- `runtime-region` counts the objects that stay until the program exits. The
  runtime region holds everything `main` allocates.
- `direct` counts the objects `main` allocated, with the results of the
  functions it calls.
- `store`, `trace` and `return` count the objects moved to the runtime region:
  by a store into an object there, by a structure linked to one, and by a
  return to `main`.
- `closes` counts how regions ended. You rarely need it.

The samples below leave out most `hint` lines.

## Rows and fixes

Built with `--mem-profile`, the report has rows such as

```
jo-mem promoted kind=store count=1000000 fun=server.handle at=server.jo:9 into=server.Stats caller=server.main
```

`fun` is the function charged, `at` its source location, and `caller` the
nearest user function around it. Each kind of row comes with a `hint` line.

| Row or hint | Cause | Change |
|---|---|---|
| `main` | `direct` is large: `main` allocates in the runtime region. | Move the loops of `main` into functions. |
| `store` | A value stored into a container owned by `main` stays until exit, even after it is overwritten. | Mutate the fields of the existing object, or store values that need no allocation. Keep per-request state in a function that returns. |
| `trace` | A store linked a structure to the runtime region, and the whole structure moved there. The row names no store. | The `store` rows whose `caller` is the `fun` of the `trace` row name the candidate stores. Fix them one at a time and run again. |
| `region` | The region of `fun` held many objects at once. They stay until `fun` returns. | Move the loop body into a function. If it already is one, its results or its stores into containers of `fun` keep the objects. |
| `library` | `fun` is a `jo.*` function. | Change how `caller` uses it, for example a `while` loop in place of `Iterator.fold` over a long input. |
| `growth` | The runtime region keeps growing while the program runs. | Fix the `growth-promoted` rows like `store` and `trace` rows. |

Primitive values in these ranges need no allocation when stored as `Any` or
in a generic container: `Int` in [-128, 1024), `Char` in [0, 256), every
`Byte` and every `Bool`.

### A loop in `main`

```jo
namespace shapes

class Point(x: Int, y: Int)

def main =
  var total = 0
  var i = 0
  while i < 1000000 do
    val p = new Point(i, i + 1)
    total = total + p.y - p.x
    i = i + 1
  end

  println total
```

Every `Point` stays until exit. The loop calls no region function, so the
`growth` line comes only at the end:

```
jo-mem growth runtime-region=1000015 peak-rss-kb=56576 direct=1000015 store=0 trace=0 return=0
jo-mem hint main: main runs in the runtime region, so what it allocates stays until exit, ...
jo-mem summary peak-rss-kb=56704 allocated=1000087 freed=72 runtime-region=1000015 skipped-enters=0
jo-mem to-runtime direct=1000015 store=0 trace=0 return=0
jo-mem closes free=2 merge=0 collect=0
```

Move the loop body into a function. Its region frees the `Point` of each
iteration:

```jo
def step(i: Int): Int =
  val p = new Point(i, i + 1)
  p.y - p.x

def main =
  var total = 0
  var i = 0
  while i < 1000000 do
    total = total + step(i)
    i = i + 1
  end

  println total
```

```
jo-mem summary peak-rss-kb=4240 allocated=1000017 freed=1000002 runtime-region=15 skipped-enters=0
jo-mem to-runtime direct=15 store=0 trace=0 return=0
jo-mem closes free=1000001 merge=0 collect=0
```

### A value stored into a long-lived object

```jo
namespace server

class Point(x: Int, y: Int)

class Stats(first: Point)
  var last: Point = first
end

def handle(stats: Stats, i: Int): Unit =
  stats.last = new Point(i, i + 1)

def main =
  val stats = new Stats(new Point(0, 0))
  var i = 0
  while i < 1000000 do
    handle(stats, i)
    i = i + 1
  end

  println stats.last.x
```

`stats` belongs to `main`, so each `Point` stored into it stays until exit,
even after the next call overwrites it:

```
jo-mem summary peak-rss-kb=56704 allocated=1000305 freed=288 runtime-region=1000017 skipped-enters=0
jo-mem to-runtime direct=17 store=1000000 trace=0 return=0
jo-mem closes free=5 merge=420 collect=999580
jo-mem promoted kind=store count=1000000 fun=server.handle at=server.jo:9 into=server.Stats caller=server.main
```

Store the numbers instead of a new object:

```jo
namespace server

class Point(x: Int, y: Int)

class Stats
  var lastX: Int = 0
  var lastY: Int = 0
end

def handle(stats: Stats, i: Int): Unit =
  val p = new Point(i, i + 1)
  stats.lastX = p.x
  stats.lastY = p.y

def main =
  val stats = new Stats
  var i = 0
  while i < 1000000 do
    handle(stats, i)
    i = i + 1
  end

  println stats.lastX
```

```
jo-mem summary peak-rss-kb=3724 allocated=1000018 freed=1000002 runtime-region=16 skipped-enters=0
jo-mem to-runtime direct=16 store=0 trace=0 return=0
jo-mem closes free=1000001 merge=0 collect=0
```

### A hidden store in a library

Library calls can store too. The iterator that `Iterator.select` returns keeps
the next match in a field, as a new `Some`. An iterator created in `main`
belongs to the runtime region, so every `Some` stored into it stays until
exit:

```jo
namespace evens

def main =
  val it = (0 until 3000000).iterator.select(i => i % 2 == 0)
  var total = 0
  while it.hasNext do
    total = total + it.next() % 7
  end

  println total
```

The report shows a `store` row in `jo.Iterator.SelectIterator$fill`. The
`trace` row counts the boxed numbers that the `Some`s point to:

```
jo-mem summary peak-rss-kb=120192 allocated=4499571 freed=1500060 runtime-region=2999511 skipped-enters=0
jo-mem promoted kind=store count=1500000 fun=jo.Iterator.SelectIterator$fill at=lib/Iterator.jo:66 into=jo.Iterator.SelectIterator caller=evens.main
jo-mem promoted kind=trace count=1499488 fun=jo.Iterator.SelectIterator$hasNext at=lib/Iterator.jo:74 caller=evens.main
```

Both rows name `jo.*` functions, so change how `main` uses the library. Count
in a `while` loop and test each number with an `if`:

```jo
namespace evens

def main =
  var total = 0
  var i = 0
  while i < 3000000 do
    if i % 2 == 0 then
      total = total + i % 7
    end
    i = i + 1
  end

  println total
```

```
jo-mem summary peak-rss-kb=4624 allocated=17 freed=2 runtime-region=15 skipped-enters=0
jo-mem to-runtime direct=15 store=0 trace=0 return=0
jo-mem closes free=1 merge=0 collect=0
```

A `while` loop over `(0 until 3000000).iterator` with an `if` is not enough.
It drops the `Some`s, but each `next` returns a boxed `Int` to `main`, and the
peak stays at 120192 KB.

## Long-running programs

A server that never exits never prints the exit report. With `JO_MEMSTATS=1`
set, the runtime also prints a `growth` line when the runtime region reaches
about 63000 objects, and again each time it doubles. The first `server`
example above prints this first:

```
jo-mem growth runtime-region=63427 peak-rss-kb=5120 direct=15 store=63412 trace=0 return=0
jo-mem growth-promoted kind=store count=63412 fun=server.handle at=server.jo:9 into=server.Stats caller=server.main
```

The same pair of lines repeats at 126915, 253891 and 507843 objects.

The counts other than `runtime-region` and `peak-rss-kb` are the objects
moved since the previous `growth` line. A single report soon after startup can
be data the program builds once and keeps, such as a table it loads. Repeated
reports mean the region grows without bound.

A growth report is printed at the next return of a region function, so a loop
in `main` that calls none reports late.

## Trading time back

The default policy gives the lowest peak RSS, but opening a region in every
function can cost time. To get some of it back, open regions only where they
matter:

1. Build with `--mem-profile` and run with `JO_MEMSTATS=1`.
2. Rebuild with `--region-policy none` and a `--region` for the function of
   the largest `region` row.
3. Compare the time and `peak-rss-kb` with the default build. Keep the
   `--region` if it lowers `peak-rss-kb`. A function whose objects are still
   live when it returns gains nothing.
4. Repeat with the next largest `region` rows, one at a time.

```sh
jo compile --llvm --region-policy none --region app.handleRequest app.jo -o app
```

On the jtk deflate bench, `deflate 6 1024`, a single `--region` kept the peak
RSS of the default build at close to the time of `--region-policy none`. The
[memory management design](https://github.com/typescope/jo/blob/main/stack-lang/llvm/GC.md#choosing-the-policy)
has the numbers.

`--region` takes the full name of a top-level function, such as
`app.handleRequest`, or `app.Section.f` for a function in a section. Class
methods and runtime functions cannot be named. When a method's row is large,
the fix is in the program, for example calling a top-level function from the
method. There is no option to remove a single region from the default policy.
Its allocations would merge into the caller's region, so RSS would not drop.

## What the numbers do not mean

- Counts are objects, not bytes.
- A `region` row counts in segments of 62 objects, so `objects` can be up to
  61 too high.
- A row is charged to the innermost region function, `fun`. `caller` is the
  nearest enclosing function outside the library. A function whose name starts
  with `jo.` counts as library code.
- Each kind lists at most 10 rows, the largest first, and leaves out rows
  under 1000 objects.
- A program that runs out of memory or overflows the stack prints no report.

The [memory management design](https://github.com/typescope/jo/blob/main/stack-lang/llvm/GC.md)
describes regions and the report in full.
