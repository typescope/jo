# Lowering Peak Memory in Native Programs

Experimental. This guide covers the LLVM backend (`jo compile --llvm`).

A native program frees memory in regions. By default every function that can
allocate runs in a region of its own, including the entry function. When the
call returns, the region frees what the call allocated, except what it
returned or stored into an older object. You need no annotations and no
options for this.

A value a function may return is allocated where its result goes, in the
caller's memory. "May return" is decided for the whole function, not for
each call: a value returned on some paths, or stored into such a value, goes
to the caller even on a call that does not return it, and stays there until
the caller's memory is freed. To free such a value with the call, allocate
it only on the paths that return it.

A function that allocates only values it may return, such as a constructor,
opens no memory of its own. A function that also builds temporaries, like
the intermediate strings of `"(" + s + ")"`, has its own memory as usual.

Loops free memory too:

> A loop in a function that has its own memory frees what its iterations
> allocated, a bounded amount at a time. The iterations run in batches of
> about 8 KB of allocations. The end of every batch but the first frees
> what the batch allocated, except what it assigned to a variable declared
> outside the loop or stored into an older object. What the first batch
> allocated stays until the function returns, or until the iteration of an
> outer loop ends.
>
> A value replaced in a variable declared outside the loop, or in a field
> or element of an object created before the loop, stays until that
> variable's or object's memory is freed, usually when its function
> returns. In a function that never returns, such values are never freed.
>
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
jo-mem closes free=0 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=0
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
- `loops` counts the batches of loop iterations that ran in memory of
  their own.

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
| `region` | The region of `fun` held many objects at once. Its loops free what their iterations allocated, except what they assign to a variable declared outside the loop or store into an older object. | What stays is what `fun` allocated outside its loops, the values its loops replaced in such variables and objects, and what its callees returned or stored into its objects. Store values that need no allocation, mutate the fields of the existing object, or move the loop into a function that returns. |
| `return` | `fun` returned this many results to code in the runtime region. They stay until exit. The `runtime` hint comes with it. | Give the caller its own memory with `--region`. Or avoid a new object per call. |
| `library` | `fun` of a `store`, `trace` or `region` row is a `jo.*` function. | Change how `caller` uses it, for example a `while` loop in place of `Iterator.fold` over a long input. |
| `traced` | The traces of closing regions lowered many more objects than were allocated. The likely cause is a recursive function whose result is built in a loop, such as a tree whose children come from `Array.tabulate`, `List.map` or `a[i] = build(...)`: every level copies the result up to its caller and traces it again. | If a node has a fixed number of children, pass them to its constructor, `Inner(build(d - 1), build(d - 1))`: a value returned through direct calls outside a loop is allocated where its caller keeps it. Otherwise build with `--region-policy none` and a `--region` for the function that calls the recursive one, or build the result with a loop or an accumulator in one function. See [A tree built in a loop](#a-tree-built-in-a-loop). |
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
frees the `Point`s of each batch of iterations, about every 8 KB:

```
jo-mem summary peak-rss-kb=4520 allocated=1000017 freed=1000004 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=3908 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=3906
```

Under `--region-policy none`, `main` runs in the runtime region instead, and
every `Point` stays until exit:

```
jo-mem summary peak-rss-kb=56576 allocated=1000017 freed=0 runtime-region=1000017 skipped-enters=0
jo-mem to-runtime direct=1000017 store=0 trace=0 return=0
jo-mem closes free=0 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=0
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
memory of `main`, and stays there after the next call overwrites it, until
`main` returns:

```
jo-mem summary peak-rss-kb=56704 allocated=1000187 freed=1000174 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=6 handoff=3906 merge=1000000 collect=0 traced=0
jo-mem loops iteration-regions=3906
jo-mem region objects=1000060 fun=server.main at=server.jo:12 caller=runtime
```

The `region` row names `main`, which holds the values. Moving the loop into
another function does not help: the values go to the memory of the object
they are stored into. Store the numbers instead of a new object:

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

Each `Point` then stays in the memory of `handle`, which frees it when it
returns:

```
jo-mem summary peak-rss-kb=4520 allocated=1000018 freed=1000005 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=1000002 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=3906
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

The iterator was created before the loop, in the memory of `main`. Finding
the next match stores the boxed value into the iterator, an older object, so
each match stays in the memory of `main` until `main` returns:

```
jo-mem summary peak-rss-kb=60672 allocated=2999220 freed=2999206 runtime-region=14 skipped-enters=0
jo-mem to-runtime direct=14 store=0 trace=0 return=0
jo-mem closes free=8 handoff=0 merge=2998978 collect=8768 traced=0
jo-mem loops iteration-regions=8768
jo-mem region objects=1499718 fun=evens.main at=evens.jo:3 caller=runtime
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
jo-mem summary peak-rss-kb=4520 allocated=17 freed=4 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=2 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=0
```

### A tree built in a loop

```jo
namespace trees

union Tree = Inner(children: Array[Tree]) | Leaf(n: Int)

def build(depth: Int): Tree =
  if depth == 1 then Leaf(1)
  else Inner(Array.tabulate(4, _ => build(depth - 1)))

def count(tree: Tree): Int =
  match tree
    case Inner(children) =>
      var n = 1
      for child in children do n = n + count(child)
      n
    case Leaf(_) => 1

def main =
  var total = 0
  var k = 0
  while k < 200 do
    total = total + count(build 8)
    k = k + 1
  end

  println total
```

Each subtree is built in the loop of `tabulate`, so it starts in the memory
of `tabulate`, not where its caller keeps it. Each return copies it one
level up, and the closes trace it again at every level. An excerpt of the
statistics, without the `region` rows:

```
jo-mem summary peak-rss-kb=4096 allocated=8737817 freed=8737804 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=1092401 handoff=0 merge=2163200 collect=46600 traced=51783800
jo-mem loops iteration-regions=37000
jo-mem traced objects=35411000 fun=jo.Array.tabulate at=lib/Array.jo:150 caller=trees.build
jo-mem traced objects=16372800 fun=trees.build at=trees.jo:5 caller=trees.main
```

A node here always has four children, so pass them to its constructor. A
value returned through direct calls outside a loop is allocated where the
caller keeps it, so nothing is traced, and the program runs about 9 times
faster. `count` matches on `Inner` and adds the counts of its four
children:

```jo
union Tree = Inner(a: Tree, b: Tree, c: Tree, d: Tree) | Leaf(n: Int)

def build(depth: Int): Tree =
  if depth == 1 then Leaf(1)
  else Inner(build(depth - 1), build(depth - 1), build(depth - 1), build(depth - 1))
```

The statistics, without the `region` row:

```
jo-mem summary peak-rss-kb=3756 allocated=4369017 freed=4369004 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=201 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=200
```

When the number of children varies, keep `tabulate` and build with
`--region-policy none --region trees.main`. `build` and `tabulate` then
open no memory of their own, so nothing is copied up, and the loop of
`main` frees each tree after its iteration:

```
jo-mem summary peak-rss-kb=5376 allocated=8737817 freed=8737804 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=200 handoff=0 merge=0 collect=0 traced=0
jo-mem loops iteration-regions=200
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
prints a `growth-region` line for the largest open region. The first
`server` example, under the default policy, prints:

```
jo-mem growth-region objects=63490 peak-rss-kb=5120 fun=server.main at=server.jo:12 caller=runtime
```

Read the line like a `region` row. The first line comes with the `region`
hint. Repeated lines for the same `fun` mean its region grows without bound.

A growth report is printed at the next return of a region function or the
next end of a batch of loop iterations that ran in memory of their own. A
loop that calls no region function and runs in no memory of its own reports
late.

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

- Counts are objects, not bytes. Loops end their batches by the bytes
  requested from the allocator, which the report does not show.
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
