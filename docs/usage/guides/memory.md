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
   exits. Any value other than the empty string and `0` turns the report on.
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
jo-mem closes free=2 merge=0 collect=0 traced=0
```

- `peak-rss-kb` is the peak RSS. This is the number to lower.
- `runtime-region` counts the objects that stay until the program exits. The
  runtime region holds everything `main` allocates.
- `direct` counts the objects `main` allocated, with the results of the
  functions it calls.
- `store`, `trace` and `return` count the objects moved to the runtime region:
  by a store into an object there, by a structure linked to one, and by a
  return to `main`.
- `closes` counts how regions ended. You rarely need it. `traced` counts the
  objects the region closes traced. Far above `allocated`, it means the same
  objects are traced again and again (see the `traced` hint).

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
| `main` | `direct` or `return` is large: `main` allocates in the runtime region, or keeps the results of the functions it calls. | Move the body of each loop of `main` into a function. Moving the whole loop only moves the objects into the region of the new function. |
| `store` | A value stored into a container owned by `main` stays until exit, even after it is overwritten. | Mutate the fields of the existing object, or store values that need no allocation. Keep per-request state in a function that returns. |
| `trace` | A store linked a structure to the runtime region, or `fun` returned it to `main`, and the whole structure moved there. The row names no store. | The `store` rows whose `caller` is the `fun` of the `trace` row name the candidate stores. Fix them one at a time and run again. |
| `region` | The region of `fun` held many objects at once. They stay until `fun` returns. | Move the loop body into a function. If it already is one, its results or its stores into containers of `fun` keep the objects. |
| `return` | `fun` returned this many results to `main`. They stay until exit. The `main` hint comes with it. | Call `fun` in the function that holds the loop body, with the code that uses its result. Or avoid a new object per call. |
| `library` | `fun` of a `store`, `trace` or `region` row is a `jo.*` function. | Change how `caller` uses it, for example a `while` loop in place of `Iterator.fold` over a long input. |
| `traced` | The traces of closing regions lowered many more objects than were allocated. The likely cause is a recursive function whose result is built in a loop: every level copies the result up to its caller and traces it again. | Build the result with a loop or an accumulator in one function. Or build with `--region-policy none` and a `--region` for a function that calls the recursive one. |
| `growth` | The runtime region keeps growing while the program runs. | Fix the `growth-promoted` rows like `store`, `trace` and `return` rows. |
| `growth-region` | The region of `fun` keeps growing while the program runs. | Fix it like a `region` row. |

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
jo-mem hint main: main runs in the runtime region, so what it allocates, and the results ...
jo-mem summary peak-rss-kb=56704 allocated=1000087 freed=72 runtime-region=1000015 skipped-enters=0
jo-mem to-runtime direct=1000015 store=0 trace=0 return=0
jo-mem closes free=2 merge=0 collect=0 traced=0
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
jo-mem closes free=1000001 merge=0 collect=0 traced=0
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
jo-mem closes free=5 merge=420 collect=999580 traced=0
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
jo-mem closes free=1000001 merge=0 collect=0 traced=0
```

### Values that reach `main` through an iterator

An iterator created in `main` belongs to the runtime region. Each element it
hands to `main` stays until exit, whether the iterator stores it or returns
it. Small `Int`s need no allocation, but the larger ones are boxed:

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

The iterator that `select` returns keeps its next match in one reusable
field, so only the first match counts as a `store`. Each match it returns to
`main` stays. The `return` row names the function that returned them:

```
jo-mem summary peak-rss-kb=60928 allocated=2999351 freed=1499632 runtime-region=1499719 skipped-enters=0
jo-mem to-runtime direct=23 store=1 trace=0 return=1499488
jo-mem closes free=7 merge=4498673 collect=1499281 traced=0
jo-mem promoted kind=return count=1499488 fun=jo.Iterator.SelectIterator$next at=lib/Iterator.jo:93 caller=evens.main
jo-mem hint main: main runs in the runtime region, so what it allocates, and the results ...
```

Dropping `select` makes it worse, as `next` now returns every number to
`main`:

```jo
  val it = (0 until 3000000).iterator
  while it.hasNext do
    val i = it.next()
    if i % 2 == 0 then
      total = total + i % 7
    end
  end
```

```
jo-mem summary peak-rss-kb=120192 allocated=2999416 freed=423 runtime-region=2998993 skipped-enters=0
jo-mem to-runtime direct=17 store=0 trace=0 return=2998976
jo-mem closes free=7 merge=420 collect=2998556 traced=0
jo-mem promoted kind=return count=2998976 fun=jo.Range.RangeIterator$next$bridge at=lib/Range.jo:55 caller=evens.main
```

The name `$next$bridge` is the wrapper that boxes the result of `next`. As
the `main` hint says, move the loop body into a function, with the call of
`next`. Each number is then returned to `step`, and freed when `step` returns:

```jo
namespace evens

def step(it: Iterator[Int]): Int =
  val i = it.next()
  if i % 2 == 0 then i % 7 else 0

def main =
  val it = (0 until 3000000).iterator
  var total = 0
  while it.hasNext do
    total = total + step(it)
  end

  println total
```

```
jo-mem summary peak-rss-kb=4364 allocated=2998995 freed=2998978 runtime-region=17 skipped-enters=0
jo-mem to-runtime direct=17 store=0 trace=0 return=0
jo-mem closes free=2998977 merge=2998976 collect=0 traced=0
```

Moving the whole loop into a function does not help. The numbers then stay
in the region of that function until it returns, and a `region` row shows
them.

A `while` loop that counts and tests each number with an `if` allocates
nothing. No element passes through an iterator:

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
jo-mem summary peak-rss-kb=3724 allocated=17 freed=2 runtime-region=15 skipped-enters=0
jo-mem to-runtime direct=15 store=0 trace=0 return=0
jo-mem closes free=1 merge=0 collect=0 traced=0
```

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

A loop in another function can keep objects too. Its region then grows, and
the runtime region stays small. When a region other than the runtime region
reaches about 63000 objects, and each time that threshold doubles, the
runtime prints a `growth-region` line for the largest open region:

```
jo-mem growth-region objects=63430 peak-rss-kb=4992 fun=memserve.serve at=serve.jo:12 caller=memserve.main
```

Here `serve` loops over requests and keeps every reply that `handle` returns
to it. Read the line like a `region` row: what `fun` allocates and the results
returned to it stay until it returns. The first line comes with the `region`
hint. Repeated lines for the same `fun` mean its region grows without bound.

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
