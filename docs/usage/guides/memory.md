# Lowering Peak Memory in Native Programs

Experimental. This guide covers the LLVM backend (`jo compile --llvm`).

A native program frees memory in regions. By default every function that can
allocate runs in a region of its own, including the entry function. When the
call returns, the region frees what the call allocated, except what it
returned or stored into an older object. You need no annotations and no
options for this.

Loops free memory too:

> At the end of each iteration of a loop, Jo may free memory that the
> loop's function allocated. It does so when the function has its own
> memory, once it has allocated 1 MB, and at least as much as the last
> collection kept, since that collection.
>
> Jo keeps every object that a variable of the function refers to, if the
> function may read that variable again: later in the loop, after the loop,
> or in a loop around it. It also keeps what an object created by a caller
> refers to.
>
> Only the memory of the function running the loop is freed. Values stored
> into a container that a caller created stay until that caller frees them:
> at the end of an iteration of one of its own loops, or when it returns.
> Under the default policy every function that can allocate has its own
> memory. Under `--region-policy none` or `scalar`, name a function with
> `--region` to give it its own memory and let its loops free it.

Regions only change peak memory, the resident set size (RSS), never what a
program computes. When a program uses more memory than you expect, the runtime
tells you where it goes, and a small change to the program usually fixes it.

## The loop

1. Run the program with `JO_MEMSTATS=1`. It prints a report on stderr when it
   exits. Any value other than the empty string and `0` turns the report on.
2. Read the `summary` and `to-runtime` lines. If `runtime-region` is a large
   share of `allocated`, or `hint runtime` appears, the runtime region is the
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
jo-mem summary peak-rss-kb=56576 allocated=1000017 freed=0 runtime-region=1000017 skipped-enters=0
jo-mem to-runtime direct=1000017 store=0 trace=0 return=0
jo-mem closes free=0 merge=0 collect=0 traced=0
jo-mem loops collect=0 marked=0 freed=0
```

- `peak-rss-kb` is the peak RSS. This is the number to lower.
- `runtime-region` counts the objects that stay until the program exits. The
  runtime region holds what code that runs in no region allocates, such as
  the entry function under `--region-policy none`.
- `direct` counts the objects allocated there, with the results of the
  functions that code calls.
- `store`, `trace` and `return` count the objects moved to the runtime region:
  by a store into an object there, by a structure linked to one, and by a
  return to code that runs in no region.
- `closes` counts how regions ended. You rarely need it. `traced` counts the
  objects the region closes traced. Far above `allocated`, it means the same
  objects are traced again and again (see the `traced` hint).
- `loops` counts the collections at the end of loop iterations, the objects
  they kept by marking, and the objects they freed.

The samples below leave out most `hint` lines.

## Rows and fixes

Built with `--mem-profile`, the report has rows such as

```
jo-mem promoted kind=store count=1000000 fun=server.handle at=server.jo:9 into=server.Stats caller=runtime
```

`fun` is the function charged, `at` its source location, and `caller` the
nearest user function around it, or `runtime` for code that runs in no
region. Each kind of row comes with a `hint` line.

| Row or hint | Cause | Change |
|---|---|---|
| `runtime` | `direct` or `return` is large: code that runs in no region, such as a function that opens none called by the entry function, allocates in the runtime region, or keeps the results of the functions it calls. | Name that function with `--region`, so its loops free their garbage and its callees' results are freed when it returns. |
| `store` | A value stored into a container of the runtime region stays until exit, even after it is overwritten. | Mutate the fields of the existing object, or store values that need no allocation. Keep the container in a function that has its own memory. |
| `trace` | A store linked a structure to the runtime region, or `fun` returned it there, and the whole structure moved there. The row names no store. | The `store` rows whose `caller` is the `fun` of the `trace` row name the candidate stores. Fix them one at a time and run again. |
| `region` | The region of `fun` held many objects at once. Its loops free what no variable read later reaches. | What stays is reached from such a variable or from a container of a caller, or was allocated by a loop that does not collect, in a function that opens no region. Put the loop in the function that owns the container, or stop keeping the objects. |
| `return` | `fun` returned this many results to code in the runtime region. They stay until exit. The `runtime` hint comes with it. | Give the caller its own memory with `--region`. Or avoid a new object per call. |
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

`main` has its own memory, like any function that can allocate. Its loop
frees the `Point`s of earlier iterations each time it has allocated about
1 MB, since no variable read later refers to them:

```
jo-mem summary peak-rss-kb=4372 allocated=1000017 freed=1000004 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=2 merge=0 collect=0 traced=0
jo-mem loops collect=30 marked=30 freed=983039
```

Under `--region-policy none`, `main` runs in the runtime region instead, and
every `Point` stays until exit:

```
jo-mem summary peak-rss-kb=56576 allocated=1000017 freed=0 runtime-region=1000017 skipped-enters=0
jo-mem to-runtime direct=1000017 store=0 trace=0 return=0
jo-mem closes free=0 merge=0 collect=0 traced=0
jo-mem loops collect=0 marked=0 freed=0
jo-mem hint runtime: code that runs in no region, such as a function that opens no region called by ...
```

Name it with `--region shapes.main` to give it its own memory again.

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

`stats` belongs to `main`, so each `Point` stored into it moves to the
memory of `main`. The loop of `main` frees the ones the next call
overwrote:

```
jo-mem summary peak-rss-kb=4372 allocated=1000019 freed=1000006 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=2 merge=13393 collect=986607 traced=0
jo-mem loops collect=30 marked=90 freed=983037
```

Only the function that created the container frees the overwritten values.
If the loop moves into a function that `main` calls once, the loop runs in
the memory of that function, and the `Point`s it stores into `stats` move to
`main`, which no longer loops:

```jo
def serve(stats: Stats): Unit =
  var i = 0
  while i < 1000000 do
    handle(stats, i)
    i = i + 1
  end

def main =
  val stats = new Stats(new Point(0, 0))
  serve(stats)
  println stats.last.x
```

```
jo-mem summary peak-rss-kb=56704 allocated=1000187 freed=1000174 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=6 merge=13485 collect=986516 traced=0
jo-mem loops collect=30 marked=0 freed=0
jo-mem region objects=1000060 fun=server.main at=server.jo:19 caller=runtime
```

The `region` row names `main`, which holds the values. Keep the loop in the
function that owns the container, as in the first version, or store the
numbers instead of a new object:

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

### Values that reach `main` through an iterator

An iterator hands each element it returns to its caller. Small `Int`s need no
allocation, but the larger ones are boxed:

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

Each match goes to the memory of `main`, and the loop of `main` frees them,
since no variable read later refers to them:

```
jo-mem summary peak-rss-kb=4372 allocated=2999003 freed=2998989 runtime-region=14 skipped-enters=0
jo-mem to-runtime direct=14 store=0 trace=0 return=0
jo-mem closes free=3 merge=4513164 collect=1484790 traced=0
jo-mem loops collect=68 marked=680 freed=1500006
```

A `while` loop that counts and tests each number with an `if` allocates
nothing, and is faster:

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
jo-mem summary peak-rss-kb=4372 allocated=17 freed=4 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=2 merge=0 collect=0 traced=0
jo-mem loops collect=0 marked=0 freed=0
```

## Long-running programs

A server that never exits never prints the exit report. With `JO_MEMSTATS=1`
set, the runtime also prints a `growth` line when the runtime region reaches
about 63000 objects, and again each time it doubles. The first `server`
example above, built with `--region-policy none --region server.handle`,
keeps every `Point` in the runtime region and prints this first:

```
jo-mem growth runtime-region=63427 peak-rss-kb=4992 direct=15 store=63412 trace=0 return=0
jo-mem growth-promoted kind=store count=63412 fun=server.handle at=server.jo:9 into=server.Stats caller=runtime
```

The same pair of lines repeats at 126915, 253891 and 507843 objects.

The counts other than `runtime-region` and `peak-rss-kb` are the objects
moved since the previous `growth` line. A single report soon after startup can
be data the program builds once and keeps, such as a table it loads. Repeated
reports mean the region grows without bound.

A function can keep objects too. Its region then grows, and the runtime
region stays small. When a region other than the runtime region reaches
about 63000 objects, and each time that threshold doubles, the runtime
prints a `growth-region` line for the largest open region. The `serve`
version above prints:

```
jo-mem growth-region objects=63427 peak-rss-kb=4992 fun=server.main at=server.jo:19 caller=runtime
```

Read the line like a `region` row. The first line comes with the `region`
hint. Repeated lines for the same `fun` mean its region grows without bound.

A growth report is printed at the next return of a region function or the
next collection of a loop. A loop collects after the bytes it allocated
doubled, so one line can cover several thresholds. A loop that calls no
region function and does not collect reports late.

## Strings built by appends

In the native backends, `s = s + piece` writes `piece` in place past the end
of `s` when the buffer of `s` has room, and doubles the buffer when it does
not. A loop of appends therefore copies each byte a constant number of times
on average. Two patterns still copy the whole string at each step:

- A prepend, `s = c + s`, copies `s` every time, so a loop of prepends takes
  time and memory quadratic in the length. 30000 prepends of two bytes keep
  about 880 MB until the function returns. Append in reverse order, or collect
  the pieces in a list and join them once.
- A fork, `t = s + "!"` followed by `s = s + x`, copies `s` at the second
  append, since `t` already took the room after `s`. A loop that forks at
  every step copies at every step.

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

- Counts are objects, not bytes. Loops collect by the bytes requested from
  the allocator, which the report does not show.
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
