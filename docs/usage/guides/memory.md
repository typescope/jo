# Lowering Peak Memory in Native Programs

Experimental. This guide covers the LLVM backend (`jo compile --llvm`).

A native program frees memory in regions. By default every function that can
allocate runs in a region of its own. When the call returns, the region frees
what the call allocated, except what it returned or stored into an older
object. You need no annotations and no options for this.

Regions only change peak memory, the resident set size (RSS). They never change
what a program computes. When a program uses more memory than you expect, the
runtime tells you where it goes, and a small change to the program usually
fixes it. This guide is for programmers and for coding agents working on Jo
programs.

## The loop

1. Run the program with `JO_MEMSTATS=1`. It prints a report on stderr when it
   exits.
2. Read the `summary` and `to-runtime` lines.
3. If they show a problem, rebuild with `--mem-profile` and run again. The
   report then names the functions responsible.
4. Take the largest row and apply the fix its hint names.
5. Run again and compare `peak-rss-kb`.

```sh
jo compile --llvm --mem-profile app.jo -o app
JO_MEMSTATS=1 ./app
```

The first lines of the report are:

```
jo-mem summary peak-rss-kb=56704 allocated=1000087 freed=72 runtime-region=1000015 skipped-enters=0
jo-mem to-runtime direct=1000015 store=0 trace=0 return=0
```

- `peak-rss-kb` is the peak RSS. This is the number to lower.
- `runtime-region` counts the objects that stay until the program exits. The
  runtime region holds everything `main` allocates.
- `direct` counts the objects `main` allocated, with the results of the
  functions it calls.
- `store`, `trace` and `return` count the objects moved to the runtime region:
  by a store into an object there, by a structure linked to one, and by a
  return to `main`.

A small `runtime-region` means regions free almost everything. Then look at
the `region` rows instead (see below).

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
| `trace` | A store linked a structure to the runtime region, and the whole structure moved there. | Find the store into the long-lived object and avoid it, as for `store`. |
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

Every `Point` stays until exit:

```
jo-mem summary peak-rss-kb=56704 allocated=1000087 freed=72 runtime-region=1000015 skipped-enters=0
jo-mem to-runtime direct=1000015 store=0 trace=0 return=0
jo-mem hint main: main runs in the runtime region, so what it allocates stays until exit, ...
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
jo-mem summary peak-rss-kb=4616 allocated=1000017 freed=1000002 runtime-region=15 skipped-enters=0
jo-mem to-runtime direct=15 store=0 trace=0 return=0
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
jo-mem promoted kind=store count=1000000 fun=server.handle at=server.jo:9 into=server.Stats caller=server.main
```

Store the numbers instead of a new object:

```jo
class Stats
  var lastX: Int = 0
  var lastY: Int = 0
end

def handle(stats: Stats, i: Int): Unit =
  val p = new Point(i, i + 1)
  stats.lastX = p.x
  stats.lastY = p.y
```

```
jo-mem summary peak-rss-kb=4616 allocated=1000018 freed=1000002 runtime-region=16 skipped-enters=0
jo-mem to-runtime direct=16 store=0 trace=0 return=0
```

## Long-running programs

A server that never exits never prints the exit report. With `JO_MEMSTATS=1`
set, the runtime also prints a `growth` line when the runtime region reaches
about 63000 objects, and each time it doubles after that. The `server` example
above prints:

```
jo-mem growth runtime-region=126915 peak-rss-kb=8576 direct=0 store=63488 trace=0 return=0
jo-mem growth-promoted kind=store count=63488 fun=server.handle at=server.jo:9 into=server.Stats caller=server.main
```

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
2. Take the functions of the `region` rows whose `objects` count is large
   compared with the peak.
3. Rebuild with `--region-policy none` and a `--region` for each of them.
4. Compare the time and `peak-rss-kb` with the default build.

```sh
jo compile --llvm --region-policy none --region Bench.words app.jo -o app
```

On a deflate benchmark, this build took 0.82 s and 135.5 MB, against 1.02 s
and 136 MB by default, and 0.77 s and 163 MB with `--region-policy none` alone.

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
