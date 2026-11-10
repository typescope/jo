# Memory Management for the LLVM Backend

This document designs memory reclamation for the 64-bit LLVM backend. It
builds on an earlier design, explicit function regions with deferred
promotion, and changes it in these places:

1. Region functions are chosen by the backend, by default every function that
   can allocate, and adjusted in backend configuration, not by a language
   annotation.
2. Cleanup traces only the objects of the closing region.
3. Cleanup needs no work list. The trace reverses pointers instead, and the
   only memory cleanup can take is index segments for survivors that move to
   an older region.
4. Survivors move to the region that requires them by handing over storage
   segments.
5. A region from which something escapes merges into its parent while a
   budget allows, and only collects when the budget is used up.
6. An object that makes up the result of a function is allocated directly in
   the region the result goes to.

The design is single-threaded, like the rest of the native runtime.

Regions are an optimization of peak memory, the resident set size (RSS). A
program without region functions is correct and frees nothing before it exits.
Regions let memory be reused, so a long-running program can stay within a
bounded RSS. Like other optimizations, they never change what a program does,
only how much memory and time it takes.

## Overview

The heap is a stack of regions. A call to a region function opens a child
region and closes it on return. All allocations go into the current region.

Every object records two region levels. Levels number the open regions from
the oldest, like depths (see [Levels](#levels)):

- `phys`: the region whose storage holds the object.
- `req`: the oldest region the object must outlive. It compares like `phys`
  or an older level. A level owned by the same region as `phys` compares like
  `phys`, even when it is numerically higher (see [Levels](#levels)).

A heap store `b.f = c` lowers `c.req` to `b.req` if needed. When a region
closes and nothing escapes it, it frees all its objects. Otherwise it merges
into its parent while a budget allows, or collects: objects whose `req` is
older than the region are escape roots, their requirement spreads to the
region's objects they reach, and the region frees every object still required
only by itself and hands the survivors to its parent.

Safety does not depend on scanning the stack. A frame only ever holds
pointers into its own region or an ancestor region, and a region outlives
every frame that can see it.

## Region boundaries

`@region` cannot be part of the language because only the native backend can
honor it. Region functions are chosen by the backend and adjusted in backend
configuration instead:

- `--region-policy all`, the default, makes every function a region function,
  except the functions of the runtime and the functions that cannot allocate
  (see [Functions that cannot allocate](#functions-that-cannot-allocate)). The
  entry function is an ordinary function (see
  [The entry function](#the-entry-function)).
- `--region-policy scalar` makes only the functions returning a primitive
  value other than `Unit` region functions, and `--region-policy none` none.
- `--region <function>` makes a function a region function under any policy.
  The flag can be repeated. The `jo` tool does not build LLVM apps yet, so
  these options go on the `jo compile --llvm` command line.

### Functions that cannot allocate

A function that cannot allocate would always close an empty region, so it
opens none. Before codegen, a fixpoint over the call graph finds the functions
that may allocate: they build a record, call the allocator of the runtime,
call a function indirectly, or call a function that may allocate. A branch that
ends in a call that never returns, like the failure of an assertion, does not
count, since the program stops there. Without that rule, the message of the
bounds check would make every array access open a region. Boxing a `Bool` or
a `Byte` does not allocate: it returns a shared box.

Leaving out a region is always safe. It only merges the function's
allocations into its caller's region.

### The runtime region

Level 0 is the runtime region. It holds what the runtime sets up before the
program starts, such as singleton objects, program arguments and the standard
streams, and what code that runs in no region allocates: the entry function
and its callees when they open no region, as under `--region-policy none`.
The runtime region never closes: the program exits instead, so freeing it
would gain nothing.

An object that escapes to level 0 lives until the program exits.

### Tail-recursive functions

Jo's tail-call optimization turns a self-recursive function into a loop. A
region function rewritten this way opens its region once, and the loop
collects that region (see
[Collecting a region in a loop](#collecting-a-region-in-a-loop)), which frees
the garbage of earlier steps.

Merging regions is always safe. It only delays reclamation. The backend
therefore may ignore a region boundary when it is inconvenient, for example
beyond the level limit.

## Object header

Slot 0 of every heap object is `cid: i32 | gc: i32`. The `gc` half has two
forms:

```
normal    [0 | phys: 15 | mark: 1 | req: 15]
on path   [1 | cursor: 31]
```

The on-path form is used only during cleanup (see below), and the mark only
during a loop collection (see [Marking](#marking)).

Level 0 is the runtime region. Levels are limited to 32767 by the `phys`
field. A region function called at the limit runs in the current region
instead of opening a new one.

Since active regions form a chain, the lowest common ancestor of two active
regions is the older one. The barrier therefore compares levels. A region
descriptor is never needed per object.

## Static data

String literals, shared boxes, interface tables and other globals are not heap
objects. They carry a header with `phys = 0` and `req = 0`, so the barrier never lowers them
and the trace never enters them.

A string literal is a static `Raw` object whose `content` points into a static
buffer object. Its `capacity` is 0, so nothing writes to the buffer. Context
parameter keys point into static buffers too. Program
arguments are copied into heap buffers at startup, and memory handed to the C
library, such as the alternate signal stack, comes from `calloc`.

## Shared boxes

A primitive value is boxed when it flows into `Any`, for example into a
generic container. A program that stores numbers in an `Array[Int]` or folds
over characters allocates a box for every such value, and these boxes make up
much of the heap. In deflate, 1.7 million of 2 million allocations were boxes
of `Int`.

Most boxed values are small, so codegen emits their boxes as static data, one
constant array per box class:

- `Int` in [-128, 1024)
- `Char` in [0, 256)
- every `Byte`
- `false` and `true`

The ranges cover bytes, characters, small counters and the lengths of deflate.
They hold 55% of the boxed `Int` values in deflate and 27% in forth, and every
boxed `Char` in deflate and html5. `Layout.scala` defines them.

A shared box is laid out like a heap object of its class, with `gc = 0` in the
header like a string literal. The `Boxing` phase calls `boxInt`, `boxChar`,
`boxByte` and `boxBool` of `Platform.jo` instead of the box constructors. For a
value in range they return the address of its box in the array, and otherwise
allocate a new box. Once optimized, boxing a small `Int` is an add, a compare
and an address computation. `Long` and `Float` are always boxed anew. The
32-bit backends do not share boxes.

The barrier never lowers a shared box, since its `req` is 0. The trace of a
closing region never enters it, since its `phys` is 0. The arrays are in
read-only memory, so a stray write to a header faults.

Only the arrays of reachable box functions are emitted. They take about 60 KB
of the executable, mostly the `Int` array, and the loader relocates their
interface table pointers at startup. A hello world grows from 38 KB to 96 KB
and still starts faster than with boxes allocated at startup.

Sharing is invisible to programs. A box is immutable: its constructor writes
`value` once, and nothing writes into a box afterwards. Jo has no reference
equality and no identity hash. Equality, hashing, ordering and printing go
through the methods of the unboxed type, and a pattern match reads the class
id and the value. The runtime compares addresses with `sameRef` only for
context parameter keys and region segments, never for boxes.

On the jtk projects, under the default policy, shared boxes halve the peak
RSS or better and make the programs faster, with the same output:

| Project | Time before → after | Peak RSS before → after |
|---|---|---|
| html5 smoke | 2.6–2.8 → 1.9–2.2 s | 186 → 93 MB |
| deflate bench | 0.60–0.73 → 0.25–0.28 s | 60 → 34 MB |
| forth smoke | 0.22–0.23 → 0.17–0.18 s | 37 → 29 MB |
| sat smoke | 0.22–0.25 → 0.06–0.07 s | 30 → 7 MB |

## Allocation

`GC.alloc` stays the single allocation entry point. It allocates the object
with `calloc`, writes `phys` and `req` as the current level, and appends the
object to the current region.

A region stores its objects in a linked list of fixed-size segments. Each
segment is an array of object pointers with a count. Allocation fills the
first segment and puts a new one in front when it is full. This is the only
place the region storage grows.

Only the first segment of a region is ever partly filled. The segment size is
therefore not a tuning knob: the index costs one word per object, plus at most
one partly filled segment per open region. A small segment keeps that waste
low for deep recursion through region functions.

Every heap allocation is an object with a header. A byte buffer, for example
of `StringOps.bytes`, `floatToStr` or `cString`, comes from `allocBuffer` and
is an object with the buffer class id. `Bytes` are objects with the bytes
class id. Neither layout has pointers.

A string's `content` field points into its buffer, past the header. The layout
table marks such a slot as interior with a fixed offset, and the trace
subtracts the offset to find the object. Every `Addr` field is such a slot,
so an `Addr` stored in a heap object must point into a buffer or a static
buffer, never into C memory or past the start of the content. The trace checks
the class id of the buffer and stops the program if the rule is broken.
Buffers hold no pointers, so the trace lowers them without descending, and an
interior slot is never reversed.

A string with `capacity > 0` has a growable buffer, the only buffer written
after a `Raw` refers to it. Its last 4 bytes hold `used`, the number of bytes
that strings on the buffer hold. An append writes only past `used`, which no
existing string covers, so no string sees the change. The new `Raw` that
shares the buffer is allocated in the current region. The buffer belongs to a
reachable string, so its region is at least as old, and the `content` slot
needs no barrier. Runtime functions open no regions and place no results, so
this holds for every append. Writes of bytes need no barrier either, since
buffers hold no pointers.

`calloc` keeps object addresses stable, and `free` frees objects one at a
time. A size-class allocator can replace it later without changing the
design.

## Pointer layout

The trace needs the pointer slots of every object. Codegen knows every class,
closure, array and box layout and emits a table indexed by class id. An entry
describes one of:

- fixed slots: a list of slot indices that hold pointers.
- reference array: every element is a pointer.
- no pointers: `Bytes`, boxed numbers, raw buffers.

The `itable` and `apply` slots point to static data and are not listed.

## Write barrier

Only mutation stores need the barrier:

```
store(b, slot, c):
  if c != null and c.req > b.req:
    c.req = b.req
    regions[c.phys].min = min(regions[c.phys].min, b.req)
  slot = c
```

The last line records the promotion for the close of `c`'s region (see
[Knowing that nothing escapes](#knowing-that-nothing-escapes)).

Initialization stores need no barrier. A new object `b` has `req` equal to the
current level, and any `c` it can refer to already has `c.req` at that level or
older. An object placed in an older region is the exception (see
[Placing results](#placing-results)).

Codegen already routes every field store through one helper, `storeField`,
which tells initialization from mutation. The barrier goes there.

Stores performed by the runtime also need the barrier:

- `RefArray.setUnchecked` writes array elements with `writeAddr`. It gets a
  barrier-aware primitive `writeRef`, which the 32-bit backends rewrite to a
  plain store.
The barrier changes only the stored object. Propagation to its children is
deferred to cleanup.

Stores into `Addr` fields get no barrier. Such a field holds memory outside
the regions, like the runtime state, or an interior pointer, which is never
mutated.

## Region entry and exit

A region function compiles to:

```
enter()                     // push a region above the current one's levels
result = body
if result is a pointer:
  require(result, p)        // p: the parent's level, recorded like the barrier
close()
return result
```

Codegen emits the exit sequence before every `ret` of the function, including
labeled returns. Jo has no exceptions and `abort` exits the process, so there
is no unwinding path.

Records and tuples are heap objects, so a result has at most one pointer to
promote.

## Cleanup

Cleanup runs no user code and traces only the objects of the closing region
`r`. It allocates no objects and no work list. The one thing it can allocate
is an index segment: a collect appends a survivor required by an older region
to that region (see `append o to q` below), which takes a new segment when
the first segment of that region is full. The segment comes from the pool,
or from `calloc` when the pool is empty. This is bounded by the survivors
that move: one segment per 62 of them, plus one partly filled segment per
destination region, the same index those objects had in `r`, whose segments
go back to the pool at the end of the sweep. Merges and the survivors handed
to the parent reuse the segments of `r`.

### Why tracing stops at the region

The earlier design follows pointers into ancestor regions. That is not
needed. When the trace reaches an object `e` in an ancestor region `Q`, it
lowers `e.req` and stops. Then `e.req` is below `Q`'s level, so `e` is an escape root of
`Q`, and its children are traced when `Q` closes. Until then they stay in a
live region and are safe.

An object `d` of `r` that `e` points to is covered too. The store into `e` ran
the barrier, so `d` survives `r` and moves up with the survivors. When `Q`
closes, the trace from `e` reaches `d` and lowers it further.

### Algorithm

A close that collects (see [Merging regions](#merging-regions) for when):

```
collect(r):
  for o in r.objects:
    if o.req < r.num:             // escape root
      lower(o, o.req)

  for o in r.objects:             // sweep
    if o.req >= r.num:
      free(o)                     // never dereferences other objects
    else if o.req >= r.parent.num:
      o.phys = r.parent.num
      keep o                      // pack survivors at the front
    else:
      q = owner(o.req)            // required by an older region
      o.phys = q.num
      append o to q
  release the empty segments
  give the survivor segments to r.parent
```

`owner(l)` is the open region whose levels contain `l`. It walks the parent
levels up from `r.parent` until it reaches a level at or below `l`. The sweep
caches the last answer, since survivors often share a `req`. A survivor that
goes directly to an older region is not visited again by the closes in
between.

`lower(root, d)` lowers `req` to `d` for everything in `r` reachable from
`root`. It is a depth-first traversal with pointer reversal
(Deutsch-Schorr-Waite). When the traversal descends into a child, the field
that pointed to the child holds the parent instead. The chain of reversed
fields is the traversal stack, and each field is restored on the way back.

```
lower(root, d):
  prev = null
  cur = root
  cur.gc = onPath(cursor = 0)
  loop:
    i = cur.cursor
    if i < pointerCount(cur):
      c = field(cur, i)
      if c != null and not c.onPath and c.phys >= r.num and c.req > d
          and pointerCount(c) > 0:
        field(cur, i) = prev      // reverse the edge
        prev = cur
        cur = c
        cur.gc = onPath(cursor = 0)
      else:
        if c != null and not c.onPath and c.req > d:
          c.req = d               // in an ancestor or a leaf: lower only
          record(c.phys, d)       // for the cleanup of its owner
        cur.cursor = i + 1
    else:
      cur.gc = normal(phys = r.num, req = d)
      if prev == null: return
      j = prev.cursor
      up = field(prev, j)         // restore the reversed edge
      field(prev, j) = cur
      cur = prev
      prev = up
      cur.cursor = j + 1
```

An object on the path is always in `r`, and its `req` is the current `d`. Both
are implied, so its whole `gc` half is free to hold the cursor. A 31-bit
cursor covers any array. The tag bit is checked first, because the levels of
an object on the path are not stored.

All younger regions are closed, so an object is in `r` exactly when its
`phys` is `r.num` or above. On the way back the trace writes `r.num`, which
compares like the object's former `phys`.

The traversal descends only into an object with `req > d`. That condition also
replaces the `processed` map of the earlier design: an object already lowered to `d` or
shallower has nothing left to propagate. Cycles end at objects on the path.

### Cost

- Space: no work list. A collect may take index segments for the survivors
  that move to an older region, as described above.
- Opening and closing a region that allocates nothing costs two calls and a few
  loads and stores. Descriptors and segments are reused, so it does not call the
  C allocator.
- Closing a region from which nothing escapes visits each object once, to free
  it. Each dead object costs one `free`.
- A merge costs the scan of the region's levels and at most one segment's worth
  of copied pointers, however many objects the region has.
- A collect visits each object of the region twice, once to find the escape
  roots and once to free or keep them.
- The trace visits the objects of the region reachable from escape roots. Within
  one close, an object is traversed once per lowering of its `req`, which is
  once in the common case. Objects outside the region are never traversed.
- A survivor required by an older region moves there directly, and the
  closes in between do not visit it again.
- A survivor required only by the parent, such as a returned value, moves up
  one region per collect and is visited by the sweep of each. While it is
  returned further, it is also traced again: `require` lowered its `req` only
  to the level of the parent, which is too young for the next close.

So a returned object costs work at every collecting boundary it passes through
alive, unless it was allocated in its final region (see
[Placing results](#placing-results)). A region pays off when most of what it
allocates dies before it closes.

The worst case is a recursive function that returns what it builds, for
example

```
def build(n: Int): Option[Node] =
  if n == 0 then None
  else Some(new Node(n, build(n - 1)))
```

Without placing results, every node survives every enclosing close. The first
closes merge, until the list exceeds the merge budget of about 500 objects.
From then on every close collects and traces the whole list, so building a
list of `n` nodes costs O(n²). With temporaries at every level, `n = 16000`
takes 4.35 seconds on an x86-64 machine. With placing results, the nodes are
allocated in the region of the first caller, each level frees only its
temporaries, and the same run takes 0.07 seconds.

Placing results misses a result built in a loop, since a variable assigned
more than once is never marked. A variant of `build` that recurses first and
then adds 10 nodes to the result in a loop, with a temporary per node, takes
50.7 seconds for 8000 levels under `all`, and 0.02 seconds under `none`. The
memory statistics show `traced=640066822` for 240179 allocated objects, and
print the `traced` hint. Both fixes the hint names help: building the list in
one loop of a single call takes 0.05 seconds, and `--region-policy none` with
a `--region` for a function that calls `build` takes 0.02 seconds and frees
all but 22 objects.

### Choosing the policy

The default policy `all` gives the lowest peak RSS. On the jtk projects it
costs between nothing and 1.4 times the time without regions, and cuts peak
RSS by 1.4 to 19 times. These numbers were taken before shared boxes (see
[Shared boxes](#shared-boxes)):

| Project | Time none → all | Peak RSS none → all |
|---|---|---|
| html5 smoke | 1.9–2.5 → 2.3–2.8 s | 985 → 186 MB |
| crypto smoke | 0.32 → 0.29–0.32 s | 172 → 9 MB |
| deflate bench | 0.31 → 0.45 s | 85 → 60 MB |
| forth smoke | 0.15 → 0.21 s | 73 → 37 MB |
| sat smoke | 0.13 → 0.18 s | 56 → 30 MB |
| url smoke | 1.24–1.50 → 1.21–1.34 s | 53 → 16 MB |

The jtk projects are not the worst case. A small function that is called
very often and can allocate pays for `enter` and `leave` on every call, even
if it allocates only on a path that never runs. In a recursive `fib(32)` that
reads its base cases through such a function, which prints a message for a
negative value, `all` takes 0.17–0.18 seconds and `none` 0.04 seconds, about
4 times as long. Such a function is a region function only because it may
allocate.

To find out where the memory of a program goes, see
[Memory statistics](#memory-statistics).

`all` can cost time. `--region-policy none` with a `--region` for a few
functions trades some of it back. The steps to pick the functions are in the
[memory guide](../../docs/usage/guides/memory.md#trading-time-back). On the
jtk deflate bench, `deflate 6 1024`, one region function keeps the peak RSS
of `all` at nearly the time of `none`:

| Build | Time | Peak RSS |
|---|---|---|
| `all` | 1.02 s | 136 MB |
| `none` | 0.77 s | 163 MB |
| `--region-policy none --region Bench.words` | 0.82 s | 135.5 MB |

`--region` accepts only top-level functions outside the runtime. A row of a
method, like `forth.Vm$loop`, cannot be named, and the fix there is in the
program. Removing a single region from `all` does not lower RSS and may not
save time, since its allocations merge into the caller's region. That is why
there is no `--no-region`.

## Memory statistics

The runtime always counts allocations, frees, objects that go to the runtime
region and the outcomes of closes. The counts are kept in the slow paths,
except a few increments in the allocation functions. With the environment
variable `JO_MEMSTATS` set to a value other than the empty string and `0`,
a program prints them on stderr when it exits normally or by `abort`.
`JO_MEMSTATS=0` turns them off, like leaving the variable unset. It does not
print them when it runs out of memory or overflows the stack, because printing
allocates.

The report has one record per line, a kind followed by `key=value` fields:

```
jo-mem summary peak-rss-kb=4740 allocated=24034 freed=10004 runtime-region=14030 skipped-enters=0
jo-mem to-runtime direct=6021 store=8009 trace=0 return=0
jo-mem closes free=3 merge=444 collect=7581 traced=0
jo-mem loops collect=0 marked=0 freed=0
jo-mem hint rebuild with --mem-profile to attribute these to functions
```

- `summary`: the peak RSS in KB, the objects allocated and freed, the
  objects in the runtime region at exit, and the enters skipped at the level
  limit. After an `abort`, the objects of the open regions are neither freed
  nor in the runtime region, so `allocated - freed` exceeds `runtime-region`.
- `to-runtime`: the objects allocated directly in the runtime region, and
  those promoted to it by a store into an object there, by the trace of a
  closing region, and by a return to code in the runtime region. These stay
  until the program exits. `direct` counts what code that runs in no region
  allocates: `Core.start`, and the entry function and its callees when they
  open no region. Under the default policy, `direct` and `return` stay near 0.
- `closes`: the closes that freed everything, merged and collected, and
  `traced`, the objects whose `req` the traces of collecting closes lowered,
  besides the escape roots. An object traced at every close it passes through
  counts once per close, so `traced` far above `allocated` means repeated
  tracing (see [Cost](#cost)). The trace counts in a local variable and adds
  it to the total once per collect.
- `loops`: the loop collections, the objects their marks visited and the
  objects they freed (see [Collecting a region in a loop](#statistics-of-loop-collections)).

Built with `--mem-profile`, the program also reports the region functions
responsible, by name. The backend then emits the names of the region
functions and classes, and nothing else changes. Each region function gets
counters, allocated at startup only when `JO_MEMSTATS` is set. Codegen
numbers the region functions from 1 and `enter` stores the number in the
region descriptor, so a store is charged to the function of the current
region, a trace to the function of the closing region, and a return to the
runtime region to the function whose region closes. Function 0 is `runtime`,
the code that runs in the runtime region.

```
jo-mem promoted kind=store count=5000 fun=memapp.remember at=app.jo:19 into=jo.Array caller=runtime
jo-mem promoted kind=trace count=5999 fun=tr.link at=tr.jo:5 caller=runtime
jo-mem promoted kind=return count=60000 fun=memret.wrap at=ret.jo:11 caller=runtime
jo-mem region objects=10044 fun=memapp.accumulate at=app.jo:25 caller=memapp.main
jo-mem traced objects=896724 fun=memquad.build at=quad.jo:10 caller=memquad.main
```

- `promoted kind=store`: the objects the function's stores promoted to the
  runtime region. `into` is the class of the object stored into last.
- `promoted kind=trace`: the objects the trace of the function's region
  promoted to the runtime region, besides the escape roots.
- `promoted kind=return`: the results the function returned to code in the
  runtime region, which `leave` promoted there. `caller` is always `runtime`.
  A result placed in the runtime region counts in `direct` instead.
- `region`: the most objects the function's region held at once. It is
  measured in segments, so `objects` is the segment count times 62, and
  overestimates by up to 61.
- `traced`: the objects the traces of the function's region lowered, the
  per-function share of `traced` in `closes`. It saturates at 2^31 - 1.

`fun` is the innermost region function. `caller` is the nearest function of
an enclosing region that is not a library function, at the last promotion or
at the peak, so it names user code even when `fun` is in the library. A
function whose name starts with `jo.` counts as a library function. Each kind
has up to 10 rows, the largest first, and leaves out rows under 1000 objects.

Past the level limit, `enter` opens no region, so the calls of a deep
recursion share the region at the limit. A nonzero `skipped-enters` then
explains a large `region` row of a recursive function.

After the rows come hints, written in the runtime. Each hint is printed only
when a row of its kind is:

- `store`: a value stored into a container of the runtime region stays even
  after it is overwritten. Moving the container helps only if its function
  returns well before the program ends. Otherwise avoid a new object per
  store. The hint lists the values with shared boxes, taken from the ranges
  of `Layout`.
- `trace`: a structure reachable from the runtime region moved there when
  the region closed. Either a store linked it to an object there, or it is a
  result returned to `main`. The root of a returned result counts in
  `return`, and the objects it reaches count in `trace`.
- `region`: the region of the function held this many objects at once. Its
  loops free what no variable read later reaches. What stays is reached from
  such a variable or from a container of a caller, or was allocated by a loop
  that does not collect, in a function that opens no region.
- `library`: a `store`, `trace` or `region` row names a `jo.*` function. The
  fix is in how the caller uses it, for example a `while` loop in place of
  `Iterator.fold` over a long input.
- `runtime`: printed when `direct` or `return` is at least 1000, also without
  names. Code that runs in no region, such as a function that opens no region
  called by the entry function, allocates in the runtime region, and what it
  allocates or is returned to it stays until exit. Naming that function with
  `--region` gives it a region, so its loops free their garbage and its
  callees' results are freed when it returns. This hint also covers a
  `return` row that names a `jo.*` function, so the `library` hint is left
  out for it.
- `traced`: printed when `traced` is more than 4 times `allocated`, also
  without names. The likely cause is a recursive function whose result is
  built in a loop, which placing results misses. The hint suggests building
  the result with a loop or an accumulator in one function, or
  `--region-policy none` with a `--region` for a function that calls the
  recursive one.

The counters of a function are 12 `i32` fields, in this order: `store`,
`trace`, `peakSegs`, `peakCaller`, `into`, `caller`, `storeBase`,
`traceBase`, `return`, `returnBase`, `traced` and `tracedCaller`. The base
fields hold the counts at the last growth report. `Regions.jo` names the
offsets.

A program that never exits never prints this report, so with `JO_MEMSTATS`
set the runtime also reports while the region grows. When the runtime region
reaches 1024 segments, about 63000 objects, and each time it doubles after
that, the program prints a `growth` line:

```
jo-mem growth runtime-region=126915 peak-rss-kb=8576 direct=0 store=63488 trace=0 return=0
jo-mem growth-promoted kind=store count=63488 fun=memsrv.handle at=server.jo:14 into=memsrv.State caller=runtime
```

`runtime-region` and `peak-rss-kb` are totals, the other counts are the
promotions since the previous `growth` line. Built with `--mem-profile`, the
`growth-promoted` rows follow, like the store, trace and return rows of the
exit report but counting since the previous growth report. The first report with
a row adds a `growth` hint on what keeps the region growing. Without names,
the first report suggests rebuilding instead. The first report whose `direct`
or `return` is at least 1000 adds the `runtime` hint.

A single growth report soon after startup can be data the program builds once
and keeps, such as a table it loads. Repeated reports while the program serves
requests mean the region grows without bound.

Any other region can grow without bound too, for example the region of a
function that loops forever and keeps a growing structure.
When a region other than the runtime region reaches 1024 segments, and each
time that threshold doubles after that, the program prints a `growth-region`
line for the largest open region:

```
jo-mem growth-region objects=63428 peak-rss-kb=4608 fun=memdirect.main at=direct.jo:13 caller=runtime
```

`objects` counts the objects of that region. Built with `--mem-profile`, the
line names its function and caller, and the first one adds the `region` hint
of the exit report. Without names, the first report suggests rebuilding
instead. The threshold is shared by all regions other than the runtime region,
so a function that builds the same large temporary structure on every call
is reported once.

The runtime notes the thresholds when a segment is added, and prints the report
at the next return of a region function or the next loop collection, once the
state is consistent. The report runs in a region of its own, so it adds
nothing to the promotions or to the runtime region. Its strings still count in
`allocated` and `freed`, but not in the bytes that loop collections count
(see [Counting bytes](#counting-bytes)).

To reduce the peak RSS of a program:

1. Run it with `JO_MEMSTATS=1`. A large `direct` points to allocation by code
   that runs in no region. Large `store`, `trace` or `return` counts point to
   promotions.
2. Rebuild it with `--mem-profile` and run it again.
3. Fix the top row, following its hint.
4. Run it again and compare `peak-rss-kb`.

## Merging regions

A close does not have to collect. It can merge the region into its parent and
leave the work to a later close. This makes small regions cheap, which a
policy that opens a region in every function needs.

### When to merge

| At close | Action |
|---|---|
| Nothing escapes | Free all objects |
| Something escapes, and the merge budget allows it | Merge into the parent |
| Something escapes, and the budget is used up | Collect |

Merging a region with no escapes gains nothing. Each dead object costs one
`free` either way, and merging only holds the memory longer.

Merging pays when something escapes. The close skips the trace and the sweep.
A survivor is then visited at one collecting close, not at every enclosing
close. A recursive builder costs O(n) until the budget is reached.

The budget bounds the garbage held by merging. A region merges only if it and
its parent have at most 8 segments together, about 500 objects. Otherwise it
collects. A loop whose own region never closes therefore holds at most about
500 merged objects, until its next loop collection (see
[Collecting a region in a loop](#collecting-a-region-in-a-loop)) frees them.

A merge hands the region's segments to the parent like a collect hands the
survivors: the partly filled first segment tops up the parent's, and the full
ones are spliced in. It copies at most one segment's worth of pointers.

### Levels

A merge splices the region's segment list into the parent's. That is O(1), but
the merged objects keep their `phys` and `req`. With plain depths, the next
child reuses the merged region's depth and the barrier misses an escape:

```
b: merged object, stale req = d+1, really lives until d closes
c: new object in the new child, req = d+1
b.f = c   →  c.req > b.req is false, so c is not lowered
the child closes  →  c is freed, and b.f dangles
```

Rewriting the merged headers would cost O(N). Instead, `phys` and `req` hold
levels. Each region has a level `num` and the highest level merged into it,
`hi`:

```
enter:            child.num = parent.hi + 1
                  child.hi  = child.num
merge c into p:   if c is non-empty: p.hi = max(p.hi, c.hi)
collect r:        survivors get phys = parent.num
```

Every object's `phys` and `req` lie in `[owner.num, owner.hi]`, and new
children are numbered above `owner.hi`. So no open region has a level in
`(owner.num, owner.hi]`, and a stale level compares like `owner.num` against
every open or future region. The barrier stays unchanged. If the real owner of
`c` is younger than that of `b`, then `b.req < c.owner.num <= c.req`, so the
barrier fires. Between stale levels of the same owner, it may lower `req`
without need, which is safe.

Close tests levels against the range of the closing region `r`. All younger
regions are closed, so an object is in `r` exactly when `phys >= r.num`. An
escape root has `req < r.num`. The sweep frees objects with `req >= r.num`.

Levels grow only when a non-empty region merges, and they are reused when a
region closes, like depths. Each merge of a non-empty child uses up a level
of its parent, until the parent holds 8 segments and its children collect
instead. A collect does not give levels back while the parent is open. So a
frame that calls many small region functions whose results it keeps uses
many levels, and deep recursion through such frames reaches the limit of
32767. In `wide`, a recursive function that calls a helper 300 times per
frame and keeps each result, every frame uses about 110 levels, and the limit
is reached at a recursion depth of about 296.

At the limit, `enter` stays in the current region, as for depths before, and
counts the call in `skipped-enters`. All deeper calls then share the region
at the limit, which frees nothing until that frame returns. A loop collection
gives back the levels merged into its region while the parent is open (see
[The collection](#the-collection)). At depth 2000,
`wide` skips 1025022 enters, and its region at the limit holds 2048976
objects at once, so the report shows a large `region` row for `wide` and
`growth-region` lines while it runs. The header keeps its layout.

### Knowing that nothing escapes

Pass 1 of a collect finds the escape roots by scanning every header. To know
whether anything escapes without it, each region descriptor records `min`, the lowest level an object of
the region was promoted to. `enter` sets `min` to the region's own `num`. The
barrier records the promotion without a second branch:

```
store(b, slot, c):
  if c != null and c.req > b.req:
    c.req = b.req
    regions[c.phys].min = min(regions[c.phys].min, b.req)
  slot = c
```

`require` on the result records its promotion the same way. The `min` is a
backend intrinsic of the LLVM runtime that emits `llvm.smin.i32`, so it lowers
to `cmp` and `cmov` at every optimization level, not to a branch. A promotion
that stays within the owner region records a level at or above its `num`,
which signals nothing.

The descriptors form an array indexed by level, `regions[level]`. So `enter`
takes `&regions[parent.hi + 1]`, and the barrier finds the descriptor with one
address computation. At 32768 levels and 40 bytes per descriptor, the array
takes 1.25 MB of address space, and only the pages for the levels in use become
resident.

At the close of `r`, let `m` be the minimum of `min` over `[r.num, r.hi]`.
After a merge, the barrier may record into the slot of a merged child, at a
level in `(p.num, p.hi]`. No open region uses those slots, so the scan of the
range finds it. Something escapes `r` exactly when `m < r.num`. A merge then
passes `m` up:

```
parent.min = min(parent.min, m)
```

An object can escape several regions at once, but the barrier records only at
the innermost one. The invariant is: if an object `o` has `req` below the
`num` of its owner region, the minimum over the owner's range is at most
`o.req`. It holds for every step that lowers a `req` or moves an object:

- The barrier and `require` record the new `req` at the owner.
- A merge keeps the child's records in the parent's range, and passes `m` up
  for the objects that escape the parent too.
- A collect moves a survivor to the parent only if its `req` is at or above
  the parent's `num`, so it does not escape the parent. Any other survivor
  moves to the region owning its `req`, where it does not escape either.
- `lower` lowers an object `e` in an ancestor to the `req` of its root, and
  records it at `e`'s owner like the barrier.

## Placing results

Without help, a returned object is allocated in the callee's region and
promoted at its close. If it is returned again, it is promoted again, one
level per close. Placing results allocates it directly in the region the
result goes to.

Each region records its **result level**, the level of the open region its
function's result goes to. By default that is the caller's level. A call whose
result becomes part of the caller's result inherits the caller's result level
instead, so a value returned through many levels is allocated where the
outermost of them keeps it.

A flow-insensitive analysis marks the values that must end up in the result
region of a function:

- the returned values
- the value assigned to a marked variable
- the fields of a marked object
- the values stored into the object of a marked variable
- the arguments of a marked call that flow into the callee's result

A variable assigned more than once is never marked, nor is a parameter that
is assigned. The analysis cannot tell which of its values is returned, and
marking all of them would keep the overwritten ones in the result region,
up to one per iteration of a loop around the call.

The marked parameters are the summary of a function for its callers. A
constructor stores its parameters into `this` and returns it, so the summary
covers constructors too. Summaries are computed on demand and cached. A call
back into a function whose summary is still being computed sees no summary,
so under mutual recursion the summaries depend on the order of analysis.
Like the rest of the analysis, this only affects performance.

A marked allocation goes to the result level, and a marked call makes its
callee inherit it. Allocations and calls inside a loop stay in the current
region, since a loop may allocate many values of which only one is returned.

```
call f, marked:   inherit()          // the next enter takes the result level
                  f(args)
enter:            child.result = inherit ? current.result : current.num
marked alloc:     allocResult(size)  // in regions[current.result]
```

A call site sets `inherit` only when the callee opens a region, so the flag is
always consumed by the callee's `enter`. Past the level limit, the current
function opened no region, and `allocResult` allocates in the current region.

Placement only affects performance. An object in the result region is older
than the current region, so its initializing stores go through the barrier,
except a function reference, like the `apply` of a closure, which is not a
heap object. If a marked value does not escape after all, it stays in the
result region until that region closes.

Placing results is on under `--region-policy all`, and `--place-results` turns
it on for the other policies.

## Collecting a region in a loop

Status: implemented. `--no-loop-collect` turns it off.

A region frees its garbage only when it closes. A loop that runs for a long
time keeps its region open, so the garbage of every iteration stays. A value
overwritten in a container stays too, because the store promoted it to the
container's region. A `mutable.Map` in the entry function updated 3 million
times kept 663 MB this way, since the entry function ran in the runtime
region, which never closes.

A loop now collects a region at the end of an iteration, in the function
that opened the region. It is exact: the roots are the variables of that
function and the escape roots the barrier already records. Nothing scans the
stack and nothing guesses whether a word is a pointer.

### The rule

A loop collects the region its function opened. The runtime counts the
bytes the program requests from the allocator. At the end of each iteration
of a loop in a function that opens a region, it checks how many bytes were
requested since the function's last collection, or since the function
started. If that is at least the size of what survived the last collection,
and at least 1 MB, it collects the region. The collection keeps an object if
a variable that the function can still read refers to it, directly or
through other objects, or if an object of an older region refers to it. It
frees the rest.

A variable counts if it is read in the loop, after the loop, or anywhere in
a loop around it. A variable keeps its last value until it is assigned again.

The garbage the region holds is then at most `max(kept, 1 MB)` plus what one
iteration allocates, where `kept` is the size of the survivors of the last
collection. A loop that drops a 1 MB buffer per iteration collects every
iteration.

The count is in bytes, not objects. A count of objects or segments would let
a loop that drops a large buffer per iteration wait for about 1000 objects,
1 GB here. Bytes bound the index too, since every object takes at least 8
bytes, as much as its index entry. So bytes are the only condition.

Both numbers depend only on the program and its input: the requested sizes,
and `kept`, measured from the objects themselves (see
[Counting bytes](#counting-bytes)). So a program collects at the same
iterations on every run and with every C library. The growth reports restore
the counter after they print, so `JO_MEMSTATS` does not change the
iterations either.

### Why the roots are complete

Let `F` be the function running the loop and `R` the region it opened. At the
end of an iteration:

- No region younger than `R` is open. Every call of the iteration returned.
  So an object is in `R` exactly when its `phys` is `R.num` or above, as in a
  close.
- No other frame runs in `R`. A function that opens no region runs in the
  region of its caller, but a callee of `F` has returned, and the callers of
  `F` run in older regions.
- Older frames hold no pointer into `R`. A frame only holds pointers into its
  own region or an ancestor (see [Overview](#overview)), and `R` is younger
  than every older frame's region.
- An older region reaches `R` only through a store, and the barrier lowered
  the stored object's `req` below `R.num`. These are the escape roots a close
  already uses, and `min` tells whether there are any (see
  [Knowing that nothing escapes](#knowing-that-nothing-escapes)).
- The frame of `F` reaches `R` through its variables. Codegen knows them and
  passes them to the runtime (see
  [The roots of the frame](#the-roots-of-the-frame)).

Globals need no roots. A singleton has no fields
(`docs/language/definitions/object-definitions.md:83`), and all singletons
are created at startup, before the entry function, in the runtime region
(`objectInitProc` in `LLVMRuntime.scala`). Their holders are written once
there (`compile(assign)` in `CodeGen.scala`). The runtime state, `State` in
`Platform.jo`, holds only `Addr` fields and numbers: descriptors, segments
and statistics in C memory. The segments point to Jo objects, but they are
the index of the regions, not roots. The context parameters of `Core.start`
are `Entry` objects in the runtime region (`start` in `Platform.jo`). The
regex engine keeps no cache: its only globals are singletons (`NFA.jo:9`).
Outside `Regions.jo`, the runtime writes a pointer with `writeAddr` only into
C memory (`Signal.jo:54`) and through `writeRef`, which runs the barrier
(`writeRef` in `Platform.jo`).

This needs `R` to be younger than the runtime region. Only a region opened
by `enter` is collected, never level 0.

### The entry function

Jo has no special `main`. `jo.main` is a deferred declaration
(`lib/Main.jo:21`). The frontend picks an implementation, a top-level
function named `main` (`detectMain` in `FrontEnd.scala`) or the one named
by `--link jo.main=app.run`, and codegen rewires `jo.main` to it (`funRef`
in `CodeGen.scala`). The chosen function is an ordinary function. It can
have another name, be called from other code, or be recursive. The only real
entry is `Core.start` (`start` in `Platform.jo`).

So the entry function opens a region like any other function: whenever the
policy or `--region` says so, and it closes the region when it returns.
`opensRegion` has no exclusion for it. Under the default policy, it opens a
region if it may allocate. Under `none` or `scalar` it runs in the runtime
region, unless `--region` names it.

This is sound. `Core.start` allocates the argument array and the context
entries of `IO.stdout`, `IO.stderr` and `IO.args` before the call, so they
are in the runtime region, older than the entry's region. Its frame holds
pointers only into level 0, as the invariant requires. A store into one of
these objects runs the barrier and makes an escape root. Static data has
`phys = 0`, below any region `enter` opens.

The cost is one close when the entry returns, before `finish`, which frees
what its region still holds object by object. The measurements include the
exit time of a program that holds a large live heap when its entry returns
(see [Measurements](#measurements)).

The runtime region then holds what the runtime sets up before the entry
function, and what is stored into it later. A program can store only into
one of those objects: the array of `IO.args`.

A collection of level 0 would need the frame of `Core.start` and the
singletons as roots. It would also need to tell static data from heap
objects, since both have `gc = 0`, and static data is in read-only memory.
Collecting only regions that `enter` opened avoids both.

### Which loops collect

Codegen adds the check to a `while` loop when both hold:

1. The function opens a region, under the policy or `--region`. A loop in a
   function that opens no region runs in the region of a caller, whose
   variables are not known here. It never collects. Under `--region-policy
   none`, only the loops of functions named by `--region` collect. Under
   `scalar`, also the loops of functions returning a primitive value other
   than `Unit`. To collect the loops of another function, such as the entry
   function, name it with `--region`.
2. The condition or the body may allocate. The scan of `Allocations.scala` runs
   on the loop instead of the whole function. A loop that only computes on
   numbers gets no check.

`--no-loop-collect` leaves out every check, for comparing time and memory
and for bisecting a failure.

Past the level limit, `enter` stays in the region of the caller (see
[Levels](#levels)). The runtime then refuses the collection, since the region
is not `F`'s. `skipped > 0` at the end of an iteration means exactly that `F`
opened no region: every callee has returned, and once an `enter` is skipped,
every deeper one is skipped too.

Nested loops each get the check when they qualify. The check is cheap (see
[Cost of a loop collection](#cost-of-a-loop-collection)), so an inner loop
keeps it.

The check runs on the SAST that codegen sees, after every lowering phase, so
the loops the frontend creates are covered:

- A `for` loop is a `while` over an iterator held in a local.
- A tail-recursive function is a `while` after `TailCallOpt`
  (`TailCallOpt.scala:14`). It opens its region once at entry, so the loop
  collects that region. Such a function now frees the garbage of earlier
  steps, which [Tail-recursive functions](#tail-recursive-functions) left out
  of scope.
- A `continue` or `break` is a return to a label. A jump to the end of the
  body still passes the check, and a jump out of the loop skips it.

### Operands computed before a loop

Codegen holds some values in LLVM registers while it compiles the next
operand. If that operand contains a loop, a collection in it would not see
them. These are the only places in `CodeGen.scala`:

- the arguments of a direct call, also of a C function and of an intrinsic
  (`compile(app)`, `callC`, `callIntrinsic`)
- the function and the arguments of an indirect call
- the new object of a record while its fields are computed
  (`compile(rec)`)
- the object of a field assignment while the value is computed

The other words have one operand (`Select`, `Assign`, `Return`, `Encoded`,
`TypeApply`), or consume it before their branches (`If`, `While`). `&&` and
`||` become an `If`. The `inherit` call of a placed call takes no operand. The
result slot of an `if` is written at the end of a branch and read right
after, never across an iteration.

A small rewrite of the SAST, `LoopOperands` in `LoopRoots.scala`, removes
these values before codegen, unless `--no-loop-collect` is given. It runs on each `FunDef` as it enters `funDefs`
(`compile(units)` in `CodeGen.scala`), not in `compileFunDef`.
`resultRegions` builds its analysis from `funDefs` and matches placement by
node identity (`allocations.contains(rec)` and `calls.contains(app)`), so a
later rewrite would silently drop placement and `inherit`. The rewrite is a
`TreeMap`, which rebuilds only the nodes it changes and the nodes above them,
never the whole tree, so every other node keeps its identity. The read pass of
[The roots of the frame](#the-roots-of-the-frame) runs after it.

- In a call, if an operand contains a `while`, every operand up to and
  including the last one that contains a `while` is assigned to a fresh local
  before the call, which then reads the locals. The rewrite tells a direct
  call exactly as codegen does, with `directCallee`: `app.funSymbol` or
  `Encoded(Ident(sym))`, only for a symbol with `Flags.Fun`. The callee of a
  direct call is never hoisted. The function of an indirect call is, and the
  local that holds it is then called indirectly.
- In a field assignment whose value contains a `while`, the object and the
  value are assigned to fresh locals first.

The operand that contains the loop is hoisted too. Otherwise codegen would
load the locals of the earlier operands into registers before it compiles
the loop, and the read pass would place those reads before the loop, so the
locals would not be roots.

`&&` and `||` are left alone. They consume their first operand before they
evaluate the second, and hoisting the second would evaluate it even when the
first decides the result.

Constants and literals are not hoisted, since codegen holds no heap
reference for them. An operand of type `void` or `Bottom` is evaluated as a
statement in its place in the block, never assigned to a local.

The result is `Block(assign to locals, the word reading them)`, which keeps
the order of evaluation. The fresh locals are ordinary locals: the read pass
sees them, they are read after the loop, so they are roots there, and the
type rule of roots applies to them like to any local. `paramKey` reads its
argument as a tree, but it has one argument, so it is never rewritten.

Codegen checks the condition the rewrite establishes. It counts the heap
references it holds in registers while it compiles later operands, in
`operands`, `compile(rec)` and the field assignment, and asserts that the
count is 0 when it emits the check of a collecting loop.

A record literal is not rewritten. It comes only from `new`, whose fields
are constants, and from a closure, whose `underlying` is such a `new`, so
its fields never contain a loop. The held-operand count still covers it.

So every loop that passes the two conditions above collects, wherever it is
in an expression. The user-facing rule has no exception for operands.

### The roots of the frame

A root is a local slot of `F` whose value is a heap reference, read by code
that can still run after the end of the iteration:

- The slot holds a heap reference: its type is `ptr` and neither `Addr` nor
  a function type. That is the rule the layout table uses for fields
  (`layoutTableGlobals` in `CodeGen.scala`), so a slot holds the same kinds of values a pointer
  field holds: null, a static object, or a heap object. A closure has a
  lambda type and points to its `ClosureTag` object, so it is a root. A
  function type is a raw code address. Only the `apply` of a closure and the
  callee of an indirect call have it (`EncodeClass.scala:283`).
- The slot is read at or after the start of the outermost loop around the
  loop, in the order codegen compiles the function. This covers reads in the
  loop, after it, and in the loops around it.

The second condition is sound because the lowered SAST is structured. From
the end of an iteration, control goes back into the loop, out of it to code
that comes later, or back into an enclosing loop. A `Return` to a label
jumps to the end of the labeled block, which comes later
(`Labeled` and `Return` in `compile(word)`), and a `Return` from the function leaves it.
So no read before the outermost loop can run again. Codegen numbers the
reads of each slot in a pass before it compiles the body and keeps the last
position (`LoopRoots` in `LoopRoots.scala`, run by `prepareLoops`).

The lowered SAST has no declarations: a local is a symbol that `Assign`
writes. So "the variables in scope" cannot be read off the tree. The rule
above needs no scopes.

A slot that is a root may not have been assigned yet, so every pointer slot
is set to null at function entry. A slot that is not a root at a collection
is never read after it, so it is not a root at any later collection either:
a later loop starts at or after the outermost loop of the earlier one, or
shares it. So a root never points to a freed object.

The phases introduce locals in these places, and all are slots under the
same rule:

- `PatternMatcher`: the scrutinee and the bound values. A match before the
  loop is not read in it, so its locals are not roots.
- `TailCallOpt`: the copies of the parameters `_p`, read in the loop, are
  roots. So are the snapshots `_tco_cap_p`, which the loop reads at the start
  of each step, and they keep the previous step's arguments. The original
  parameters are only read before the loop.
- `LowerContextParams`: a `with` assigns the extended context to a local
  (`LowerContextParams.scala:313`). It is a root only while code that can
  still run reads it. A function receiving context parameters gets a
  `__ctx` parameter.
- `ElimCapture`: a lambda becomes a class whose fields hold the captured
  values, so a capture is a field of an object a slot reaches.
- `EncodeClass`: the receiver of a lambda call that is not idempotent goes
  into a local `o` (`EncodeClass.scala:300`).
- The iterator of a `for` loop is read in its condition, so it is a root
  during that loop and not during a later one.

LLVM promotes the slots to registers. The collection reads the roots in a
block that only runs when it triggers, so they stay live across the loop but
cost nothing on the fast path. No LLVM value derived from a root breaks: the
objects never move, and the runtime call is opaque, so no load is moved across
it.

### Counting bytes

`State` gets `requested: Long`, the bytes requested from the allocator since
startup. `alloc` and `allocResult` add the requested size, one add next to
the count of allocations they already keep. Merges, closes and sweeps do not
touch it, and the region descriptors stay as they are.

A frame with a collecting loop keeps one local, `next`: the value of
`requested` at which its next collection is due. At function entry it is
`Regions.loopStart()`, which returns `requested + 1 MB`. A collection returns
`requested + max(kept, 1 MB)`. The check compares `requested` with `next`.

`kept` is the size of the objects the mark reached, summed in pass 3 of the
collection, the only place it is measured. The header does not store the
size, so it comes from the object:

- A class instance or a closure: its size by class id, from the table
  `@gc.sizes` that codegen emits next to the layout table, with
  `Layout.classInstanceSize` and 3 slots for a closure (`objectSize` in
  `CodeGen.scala`). Codegen asserts that each record literal allocates the
  size of its class.
- An array: `(2 + length) × 8` bytes, with the length in elements in
  slot 1.
- `Bytes`: `16 + size` bytes, with the size in bytes in slot 1.
- A buffer: its header only. A buffer does not record its size, and the
  strings on it may cover only part of it. Counting only its header makes
  `kept` smaller and collections earlier, never later.

Requests count what the program allocates in all regions, including the
garbage of callees that their own close frees. A loop whose calls free their
own garbage therefore still collects once per `max(kept, 1 MB)` requested
bytes. Each such collection traces the live data of `R` and sweeps `R`, so
the cost stays proportional to what was requested.

### The collection

`collectLoop(roots, n)` collects the current region in place:

```
collectLoop(roots, n):
  if skipped > 0: return never          // F opened no region, the largest Long
  r = current region, num = r.num
  if min over [num, r.hi] < num:        // pass 1: escape roots, as in a collect
    for o in r.objects:
      if o.req < num: lower(o, o.req)
  for v in roots:                       // pass 2: mark from the frame
    if v != null and v.phys >= num and num <= v.req <= r.hi:
      mark(v)
  kept = 0
  for o in r.objects:                   // pass 3: sweep in place
    if o.marked:
      o.gc = normal(num, num)
      keep o                            // packed at the front
      kept += size(o)
    else if o.req >= num:
      free(o)
    else:
      q = owner(o.req)                  // reached from an older region
      o.phys = q.num
      append o to q
  r.hi = num
  r.min = num
  if growth report due: print it        // marks are cleared now
  if mode == all: return requested      // collect at every end of an iteration
  if mode == often: return requested + kept
  return requested + max(kept, 1 MB)
```

Pass 1 is the first pass of a collect, `spreadEscapes`, which both share.
After it, every object of `R` that an escape root reaches has `req` below
`num`, so the mark can stop at such an object.

Pass 3 is the sweep of a collect with two differences. A
marked object stays in `R`. An object reached from an older region moves to
the region that owns its `req`, also when that is the parent: `R` stays open,
so it cannot hand the object to the parent with its segments. The sweep of a
close moves only objects whose `req` is below the parent's level and keeps
the others for the parent. Both are `pack`, which packs the survivors the
same way and caches the last `owner`. The packed segments stay in `R`, the partly filled one
first, and `firstSeg`, `lastSeg` and `segs` are set to them.

After pass 3, every object in `R` has `phys = req = num`, and nothing in `R`
escapes. No object anywhere has a level in `(num, hi]`: the survivors were
rewritten, the moved objects have a `req` below `num`, and the objects of
older regions have levels below `num`. So `R` gives back the levels of the
regions merged into it, and its `min` starts again at `num`. This also helps
a frame that calls many small region functions in a loop and would otherwise
use up levels (see [Levels](#levels)).

### Marking

`mark(root)` is `lower` with three changes:

- It descends into or marks an object only if `phys >= num` and
  `num <= req <= r.hi`, that is in `R`, not reached from an older region, and
  not marked yet. The bounds also keep it out of static data, whose `phys` is
  0, below `num`. So it never writes read-only memory.
- An object without pointers, such as a buffer, is marked, not lowered. An
  object of an older region is left alone, not lowered or recorded.
- On the way back it writes `normal(num, num)` with the mark set, where
  `lower` writes `normal(num, d)`.

The mark is bit 15 of the `req` field. Levels stop at 32767, so `req` never
uses that bit, and the header becomes:

```
normal    [0 | phys: 15 | mark: 1 | req: 15]
on path   [1 | cursor: 31]
```

The mark exists only during `collectLoop`, which runs no user code, and pass 3
clears it. No barrier sees a mark. The mark needs no extra memory, unlike a
side bitmap, which would need an address-to-index map and allocation during
the collection.

Lowering `req` to the parent's level instead of marking does not work: the
parent's highest level, `num - 1`, is also the `req` of a real escape into an
object the parent merged. A marked object and an escaped one could not be told
apart.

Interior pointers need nothing new. A root is never an `Addr`, and the trace
reads an interior slot with `getRef`, which finds the buffer header and checks
its class. A buffer is a leaf, so it is marked and never descended. A growable
buffer whose strings are all dead is freed with them. Its `used` count only
grows, and no live string reads past it.

### Escape roots, merged regions and placement

- An escape root is an object of `R` with `req < num`. It survives and moves
  to its owner. What it reaches in `R` is lowered by pass 1, survives and
  moves too. This is what a close does, so the invariant on `min` holds.
- A region merged into `R` owns levels in `(num, hi]`. Its objects have
  `phys >= num`, so they are in `R`. Its escapes are recorded in the `min` of
  its level, which the scan of `[num, hi]` covers.
- `R` itself is never shared: the frame that opened it is the only one that
  collects it, and only while no younger region is open.
- A placed result is allocated in the result region of `F`, older than `R`.
  Its initializing stores run the barrier (`compile(rec)` in
  `CodeGen.scala`), so what it reaches in `R` is an escape. Allocations inside a loop are never placed
  (`ResultRegions.scala`).
- When `F` returns, `R` closes as before. Its survivors have
  `phys = req = num`, which a close handles like any object of `R`.

### Code generation

For a function with a collecting loop, `prepareLoops` adds two allocas at
entry: `%loop.next`, an `i64` set by `Regions.loopStart()`, which is inlined
like `requested`, and `%loop.roots`, an array as large as the most roots of
one of its loops. Every pointer slot is set to null at entry. At the end of
the loop body, if the body did not end in a jump, `checkLoop` emits:

```
  %req  = call i64 @Regions.requested()      ; inlined: one load
  %next = load i64, ptr %loop.next
  %due  = icmp sge i64 %req, %next
  br i1 %due, label %collect.N, label %while.cond.N

collect.N:                                   ; emitted after the body
  ; store each root slot of this loop into %loop.roots
  %new = call i64 @Regions.collectLoop(ptr %loop.roots, i32 <n>)
  store i64 %new, ptr %loop.next
  br label %while.cond.N
```

`collectBlocks` emits the slow blocks at the end of the function, when all
slots are known, since `localSlot` creates them as codegen meets them. Each
loop stores its own roots, and a loop without roots passes `null`. `next` is
a local of the frame, since `F` owns `R` for the whole call.

`collectLoop` is `noinline`, like `enter` and `leave`, so LLVM treats it as an
opaque call that may write memory and free objects. `requested` reads the
counter of the runtime state and is inlined like the barrier.

Codegen also asserts that it converts an integer to a pointer only for the
constant 0 outside the runtime (`coerce` in `CodeGen.scala`). A root is
then never a number in disguise.

### Statistics of loop collections

One new line in the exit report, printed in every report after `closes`:

```
jo-mem loops collect=812 marked=1904331 freed=2998120
```

`collect` counts loop collections, `marked` the objects their marks visited,
and `freed` the objects they freed, which also count in `freed` of `summary`.
`marked` far above the objects a program keeps means a loop collects a large
live structure often, for example a large structure built before a loop
that adds little garbage per iteration. One line of totals keeps the report
short. A line per collection would print thousands of lines for a long loop.

`collectLoop` also prints a growth report that is due, like `leave`. It does
so after pass 3, when the marks are cleared, since `reqOf` includes the mark
bit. `growthReport` restores `requested` after printing, for `leave` and
`collectLoop` alike. A collecting loop that calls no region function then
reports when it collects. Since a collection comes after the requested bytes
doubled, one report can cover several thresholds.

Since the entry function opens a region, the output changed:

- Function 0 is `runtime`: entry 0 of `@gc.funNames`, which named the entry
  function before, names `runtime`. The entry function gets an ordinary
  number when it opens a region. `callerOf` and `printTop` treat function 0
  as the code in the runtime region, with no other case for it. A `return`
  row prints caller 0, `runtime`: such a result went to code in the runtime
  region.
- `direct` counts the objects allocated in the runtime region, and `return`
  the results returned to it, by code that runs in no region: `Core.start`,
  and the entry function and its callees when they open no region. Under the
  default policy both stay near 0. Under `none` or `scalar` they count what
  they counted before.
- The `main` hint became the `runtime` hint, printed on the same counts:
  "jo-mem hint runtime: code that runs in no region, such as a function that
  opens no region called by the entry function, allocates in the runtime
  region, and what it allocates or is returned to it stays until exit. Name
  that function with --region, so its loops free their garbage and its
  callees' results are freed when it returns." The rest of the hint logic
  stays.
- A `region` row or `growth-region` line names the entry function like any
  region function, with the `region` hint.
- The `region` hint states the loop rule: "the region of this function held
  this many objects at once. Its loops free what no variable read later
  reaches. What stays is reached from such a variable or from a container of
  a caller, or was allocated by a loop that does not collect, in a function
  that opens no region."

### What users learn

The [memory guide](../../docs/usage/guides/memory.md) states the rule and its
limits in these words:

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

The guide's sections "A loop in `main`", "A value stored into a long-lived
object" and "Values that reach `main` through an iterator" show what loop
collection fixes under the default policy, as examples of the rule. The
second one also shows a loop moved into a callee, which leaves the values in
the region of `main`. The advice to use `--region` under `none` and `scalar`
stays.

### What it does not fix

- A loop in a callee collects the callee's region, not the caller's. In
  `cache.foreach(...)`, the loop is in the frame of `foreach`, and the values
  replaced in `cache` stay in the region of the function that created
  `cache`. The same holds for `def fill(m) = while ... m(k) = v` called once
  from `run`: the overwritten values escape `fill` and move to the region of
  `run` at the next collection. They are freed only if `run` opens a region
  and one of its loops ends an iteration afterwards, or when `run` returns.
  Restructuring stays the fix: put the loop in the function that owns the
  container.
- A value stored into the array of `IO.args` is promoted to the runtime
  region and stays until exit, like every value stored into the runtime
  region. Singletons have no fields, so they hold no such state.
- A recursion that is not a tail call has no loop, so its regions collect only
  when they close.
- A variable that is read later holds what it refers to until it is assigned
  again. The snapshots of a tail-recursive loop keep one step's arguments.
- A loop collects only if its function opens a region. Under
  `--region-policy none` or `scalar`, the entry function and most others open
  none: name them with `--region`. Past the level limit, a loop does not
  collect either.
- A single iteration that allocates much is not bounded.

### Cost of a loop collection

- Each end of an iteration of a qualifying loop: one load of the counter, a
  compare against a register and a predicted branch.
- Each allocation: one more add, of the requested size to the counter.
- A collection: pass 1 visits every object only if something escaped. Pass 2
  visits the live objects of `R` that the frame reaches. Pass 3 visits every
  object of `R` and reads the size of each survivor. The next collection
  waits until at least `kept` more bytes were requested, so each requested
  byte pays a constant amount on average.
- A loop that builds live data in its region traces it at about 1, 2, 4 MB
  and so on, about twice the final size in total, plus the sweeps, when all
  it requests stays live. When a share `f` of the requested bytes stays
  live, each collection comes at `1 + f` times the previous one, and the
  marks add up to about `(1 + f) / f` times the final size: 2.8 times for the
  large map of [Measurements](#measurements). If it costs too much, a larger
  factor or minimum trades memory for time.
- Keeping the roots live across the loop can add register pressure in a hot
  loop. They are only read in the cold block, so LLVM can spill them once
  before the loop.

### Staging

The checks go into every function that opens a region, not only into
`--region` functions. The code is the same either way: limiting it saves no
implementation and only hides bugs from the tests. Small loops cost one
compare per iteration, and the 1 MB minimum keeps most of them from
collecting in normal runs. The risk is a wrong root in some lowering of the
library, which runs in every program. The normal runs of the tests rarely
reach 1 MB in a loop, so they do not show it. The stress modes below make
loops collect far more often, and the ASan runs use them.
`--no-loop-collect` turns the checks off when a failure needs bisecting. After
the [measurements](#measurements), loop collection stays on by default.

`JO_LOOP_COLLECT`, read at startup like `JO_MEMSTATS`, picks a stress mode
without a rebuild:

- `all`: `loopStart` and a collection return `requested`, so every
  qualifying end of an iteration collects. This finds most wrong roots, but
  a loop over large live data becomes quadratic. It is for `tests/pos` and
  `tests/gc`.
- `often`: no 1 MB minimum. `loopStart` returns `requested`, and a
  collection returns `requested + kept`. The cost stays amortized, so it
  suits the jtk runs.

### Implementation size

| File | Change | Lines added, removed |
|---|---|---|
| `Regions.jo` | `collectLoop`, `mark`, `pack` shared with a close, `sizeOf`, `requested` in `alloc` and `allocResult`, `requested`, `loopStart`, stress modes, `spreadEscapes` and `lowestPromotion` shared with a close, stats, hint rewrites | 314, 46 |
| `Platform.jo` | counters, `JO_LOOP_COLLECT` | 35, 4 |
| `CodeGen.scala` | no exclusion of the entry function, function 0 renamed, `@gc.sizes`, null slots, check and slow blocks, held operands, assertions, flag | 168, 31 |
| `LoopRoots.scala` | new: the operand rewrite `LoopOperands` and the read pass `LoopRoots` | 218 |
| `Compiler.scala` | `--no-loop-collect` | 3, 2 |
| `Allocations.scala` | expose the scan for a loop | 6 |
| `LLVMRuntime.scala` | 4 symbols: `requested`, `loopStart`, `collectLoop` and `sizeTable` | 4 |
| `tests/custom/gc-memstats` | expectations, builds under `none` | 139, 62 |
| `tests/gc`, `tests/custom/gc-regions` | new tests | 555 |
| `GC.md`, memory guide | rule, statistics, three examples | prose |

The runtime is larger than estimated, mostly because of comments.

The 32-bit backends allocate with a bump allocator that never frees and are
not affected.

### Tests

The tests in `tests/gc` run normally and under `JO_LOOP_COLLECT=all`, also
with AddressSanitizer: keep the IR with `--keep-ll`, add `sanitize_address`
to the attribute groups `#0` and `#1`, build with `clang-15 -fsanitize=address`
and run under `ASAN_OPTIONS=detect_leaks=0 setarch -R`. `tests/pos` runs the
same way. Their output must not change.

- `loop-map.jo`: a `mutable.Map` in the entry function updated in a loop,
  then read in full.
- `loop-vars.jo`: a variable assigned before the loop and read after it, one
  assigned in the body and read in the next iteration, and one read only
  before the loop.
- `loop-escape.jo`: a loop storing into a container of the caller, read after
  the function returns, and an escape root reaching a chain of objects in
  `R` that the frame no longer reaches.
- `loop-nested.jo`: nested loops, an inner loop that does not allocate, and
  `break` and `continue`.
- `loop-operands.jo`: a loop in the second argument of a call, in the third
  argument of a runtime function, the `Array` update, and in the value of a
  field assignment. User code cannot call a C function or put a loop into a
  record literal, so those cases have no test.
- `loop-indirect.jo`: a loop in the second argument of a lambda call and of
  an interface method call.
- `loop-tailrec.jo`: a tail-recursive function building a list.
- `loop-with.jo`: a `with` around a loop, read inside it, and a closure
  created before the loop and called in it.
- `loop-strings.jo`: strings appended in a loop, with growable buffers shared
  between live and dead strings.
- `loop-method.jo`: a collecting loop in a method and in a lambda body.
- `loop-level-limit.jo`: a region function with a loop, called past the level
  limit.
- `loop-merge.jo`: a loop calling small region functions whose results merge
  into `R`, and recursion with a collecting loop per level.

`tests/custom/gc-regions` builds `app.jo` again under the default policy and
with a region for the entry function only, under the same 200 MB limit. It
adds `buffer.jo`, one large array dropped per iteration under a 100 MB limit
of address space, `entry.jo`, an entry function linked with
`--link jo.main=memtest.run` that calls another function named `main`
twice, and `self.jo`, an entry function that calls itself, bounded by a count
it reads from `IO.args[0]` and stores back decremented. Both store into
`IO.args`, which checks the escape into the runtime region, and run normally
and under `JO_LOOP_COLLECT=all`.

`tests/custom/gc-memstats` checks the default policy, where the entry
function opens a region, and keeps the checks of the runtime region in builds
under `none`:

- `app.jo` under the default policy: no store rows, no store, runtime or
  traced hint, `direct` below 100, a `region` row for `accumulate` with
  `caller=memapp.main`, a `loops` line, and the library hint from a `region`
  row of `jo.Iterator$fold`, which a new last loop of `main` uses to build a
  long list.
- `app.jo` under `--region-policy none --region memapp.remember --region
  memapp.accumulate`: the `remember` store row with `caller=runtime`, the
  store hint, `direct >= 3000` and the `runtime` hint, also without names.
- `server.jo` under the default policy: no `growth` line, and a `loops` line
  with `freed` above 250000. Under `--region-policy none --region
  memsrv.handle`: the three runtime growth reports, the `growth-promoted`
  rows with `caller=runtime`, the growth hint and the delta checks.
- `direct.jo` loops 520000 times. Under `none`, the runtime growth reports
  come from `tick`, a function named by `--region` that the loop calls.
  Without a region function they would not be printed before exit. Under the
  default policy, the region of `main` grows: five `growth-region` lines with
  `fun=memdirect.main`, the `region` hint, and the rebuild hint without
  names. These take over the `growth-region` checks of `serve.jo`. Under
  `--region-policy none --region memdirect.main`, the loop calls no region
  function and prints the reports itself.
- `ret.jo` returns objects from `wrap`, a top-level function, since
  `--region` cannot name a method like `RangeIterator.next`. Under `none`
  with `--region memret.wrap`: `return=60000`, a `return` row for `wrap`
  with `caller=runtime` and the `runtime` hint. Under the default policy:
  `return=0`, no `return` row, and a `loops` line that freed the results.
- `serve.jo`: no `growth-region` line, and a `loops` line.
- `quad.jo`: unchanged. Each level of `build` stays far below 1 MB, so its
  loop never collects, and `caller=memquad.main` comes from the entry's
  number.

### Changes elsewhere

The text that said the entry function, or `main`, runs in the runtime region
was updated: in this document [Region boundaries](#region-boundaries),
[The runtime region](#the-runtime-region),
[Tail-recursive functions](#tail-recursive-functions),
[Memory statistics](#memory-statistics), [When to merge](#when-to-merge),
[Levels](#levels) and [Limitations](#limitations), the comments of
`Regions.jo` and `Platform.jo` (`mainHinted` became `runtimeHinted`), and the
[memory guide](../../docs/usage/guides/memory.md).

### Measurements

Time is the best of 3 to 5 runs on a quiet x86-64 machine. "Before" is the
compiler before this change, where the entry function ran in the runtime
region. "No collect" is `--no-loop-collect`, where the entry function opens a
region but no loop collects. All use the default policy.

| Program | Before | No collect | After |
|---|---|---|---|
| `mutable.Map` in the entry function, 3 million updates of 1000 string values | 1.42 s, 724 MB | 1.67 s, 724 MB | 1.17 s, 3 MB |
| Server loop in the entry function, a new node pair stored per request, 3 million requests | 1.06 s, 440 MB | 1.22 s, 440 MB | 0.84 s, 3 MB |
| Loop in a function building a live list of 3 million nodes | 0.76 s, 279 MB | 0.88 s, 279 MB | 1.19 s, 279 MB |
| Tight loop, one dead object per iteration, 30 million iterations | 2.32 s, 1160 MB | 2.30 s, 1160 MB | 1.27 s, 3 MB |
| Map of 3 million entries kept until the entry function returns | 3.16 s, 1151 MB | 4.32 s, 1151 MB | 5.69 s, 796 MB |

A loop without allocation gets no check, and its time does not change.

The cost has two sources.

- The entry function now closes its region when it returns, like any region
  function, and the close frees every object it holds one by one. Before,
  the entry function ran in the runtime region, which the program never
  frees. The large map frees 12 million objects at exit where it freed 1569
  before, which is most of the step from 3.16 to 4.32 seconds: with `free`
  stubbed out, the gap mostly goes. This is the price of the entry function
  being ordinary. A special case that skips the frees of its region would
  bring back the difference between the entry function and other functions,
  which this design removes.
- A loop that builds live data traces it again at each collection. The live
  list of 3 million nodes adds about 0.3 seconds. The large map adds about
  1.4 seconds: its 88 loop collections mark 21.5 million objects.

The 88 collections of the large map break down by loop:

| Loop | Collections | Marked | Freed |
|---|---|---|---|
| `main`, the updates | 14 | 21518160 | 3906909 |
| `fold` over the map, which boxes each sum | 68 | 68 | 2977352 |
| `rehash`, which fills the new bucket array | 6 | 6 | 0 |

The loop of `fold` frees about 44000 dead boxes per collection and marks
only its accumulator, so its 68 collections are cheap. All the marking is in
the 14 collections of `main`. They come at growing values of `requested`,
each one 1.55 times the previous, not 2 times. A collection is due once the
program requested `kept` more bytes, and in this loop 55% of the requested
bytes stay live: 268 MB of 487 MB at the last collection. So each
collection finds a heap 1.55 times larger and marks all of it, and the marks
add up to about 1.55 / 0.55 = 2.8 times the final live objects, 21.5 million
for 7.8 million. The garbage of callees in `requested`, which the cost note
of [Counting bytes](#counting-bytes) mentions, only makes some intervals
longer, 1.14 to 1.22 times `kept`. Counting a buffer by its header makes
`kept` about 10% smaller than the live bytes, a small part of the effect. The
trigger itself, `requested` growing by `kept`, sets the factor: marking stays
proportional to the bytes requested, but the constant depends on the share
of requested bytes that stays live.

`tests/performance`, before and after:

| Program | Before | After |
|---|---|---|
| `array-fill` | 0.08 s, 32 MB | 0.06 s, 31 MB |
| `large-list` | 0.24 s, 34 MB | 0.34 s, 18 MB |
| `string-deep-rope` | 0.09 s, 22 MB | 0.07 s, 3 MB |
| `string-lines` | 0.02 s, 6 MB | 0.02 s, 4 MB |
| `string-indexOf-large`, `string-trim` | 0.00 s, 1 MB | 0.00 s, 1 MB |

These were taken on a loaded machine, as were the jtk runs. The jtk smoke
runs of sat, forth, deflate, html5, url and crypto print the same output with
and without `--no-loop-collect`, under `JO_LOOP_COLLECT=often`, and under
`often` with AddressSanitizer. Their time and peak RSS stay about the same,
since their loops keep little garbage: html5 1.58 s and 52 MB without loop
collection, 1.48 s and 51 MB with it. The runs under `scalar` and `none` and
the bench runs were not measured.

## Interaction with the optimizer

The design holds under LLVM's optimizations:

- Inlining a region function keeps `enter` and `leave`, which are explicit
  calls. Losing a boundary would only merge regions, which is safe.
- The barrier is inlined code with a store. Removing a dead heap store leaves
  the barrier's promotion in place, which is conservative.
- No load can move across `leave`, because LLVM sees an opaque call that may
  write memory.
- Allocations are never removed, because every object escapes into its region's
  segment.

`enter` and `leave` are marked `noinline` to keep the boundary opaque.
The barrier may be inlined.

## Limitations

- Under `--region-policy none` without `--region`, a program frees nothing
  before it exits.
- Objects promoted to the runtime region stay until the program exits.
- Garbage accumulates inside a long-running region whose function does not
  loop, or opens no region, or is past the level limit. A loop collects only
  the region of its own function (see
  [What it does not fix](#what-it-does-not-fix)).
- A value stored into a long-lived object is promoted to that object's region,
  and stays there after the field is overwritten, until the next loop
  collection of that region or its close. Before shared boxes and loop
  collection, most of the peak RSS of html5 under `all` was such values,
  about 1.5 million objects, in the runtime region. Such a value still stays
  when the function that created the object opens no region or does not
  loop, for example when the loop is in a callee, and when the object is in
  the runtime region.
- A returned value that placing results misses, for example one built in a
  loop, is traced again at every enclosing close that collects (see Cost).
- A merge holds the garbage of the merged region until a later close, up to
  the budget of 8 segments per region.
- A removed escape stays promoted until its destination closes, as in the earlier design.
- `calloc` per object is slower than the current bump allocator.
- Memory statistics charge a promotion to the innermost region function, and
  count objects, not bytes. Telling library functions apart by the `jo.`
  prefix of their names is a heuristic.
- A growth report is printed at the next return of a region function or the
  next loop collection, so a loop that calls no region function and does not
  collect reports late, at the next return. A loop collection comes after the
  requested bytes doubled, so its reports can cover several thresholds in one
  line. A region that reached the threshold and closed before then is not
  reported.

## Later: a faster allocator

Allocating each object with `calloc` and freeing it with `free` is simple and
correct, but slower than the bump allocator the backend uses today.

A faster scheme gives each region its own large chunks and bump-allocates
objects in them. Freeing a whole chunk at once is cheap, but a chunk can only be
freed when none of its objects survived. A survivor keeps its address, so it
stays in the chunk of the closed region. The allocator must then count the
live objects of each chunk and free the chunk only when the count reaches zero.

This changes only the allocator, not the region design.

## Implementation

The runtime is `runtime/native/libc/Regions.jo`. Region descriptors and
segments come from `calloc`:

- The region descriptors form one array indexed by level, allocated at
  startup. A descriptor is 40 bytes, so the array takes 1.25 MB of address
  space, and only the pages of the levels in use become resident. `enter`
  takes the descriptor above the current region's levels. The last field,
  `fun`, numbers the function that opened the region, for the memory
  statistics.
- A segment is 512 bytes: a `next` pointer, a count and 62 objects. Only the
  first segment of a region is partly filled. Released segments go to a pool
  of at most 64.
- A region gets its first segment on its first object.
- `min` is computed with an intrinsic of the LLVM runtime, `minInt`, which
  the backend emits as `llvm.smin.i32`. It lowers to `cmp` and `cmov` at every
  optimization level.

A region call that allocates nothing therefore allocates nothing at all. It
costs the two calls of `enter` and `leave` and a few loads and stores. Objects
are allocated with `calloc` and freed with `free`.

### Memory structure

![Regions, segments and objects, and a close](gc-regions.svg)

The runtime state is a static global. It points to the descriptor array and
to the current region, and each region records the level of its parent, down
to the runtime region at level 0. Segments index the objects of a region. They
hold pointers, never the objects themselves. `counters` stands for the fields
of the memory statistics.

```
@runtime.state (static, zeroed)
┌───────────┬──────────┬───────┬─────────┬─────────┬──────────┬───────────┬─────────────┬──────────┬──────────┐
│ regions ● │ region ● │ level │ skipped │ inherit │ counters │ segPool ● │ segPoolSize │ stackLow │ stackTop │
└─────┼─────┴────┼─────┴───────┴─────────┴─────────┴──────────┴─────┼─────┴─────────────┴──────────┴──────────┘
      │          │                                                  └──► free segment ──► …   (at most 64)
      ▼          ▼
regions: one descriptor per level (40 bytes each)
┌───────────┬───┬───┬───┬─────┬─────┬─────┬──────────────────┐
│ 0 runtime │ 1 │ … │ p │ p+1 │ p+2 │ num │ unused … 32767   │
└───────────┴───┴───┴───┴─────┴─────┴──┼──┴──────────────────┘
                     └─ levels of p ─┘ │   p.hi = p+2: the regions merged into p
                                       ▼
current region descriptor
┌────────────┬─────────┬─────────┬──────────┬────────────┬───────────┬────────────┬─────────┐
│ parent i32 │ hi i32  │ min i32 │ segs i32 │ firstSeg ● │ lastSeg ● │ result i32 │ fun i32 │
└────────────┴─────────┴─────────┴──────────┴─────┼──────┴─────┼─────┴────────────┴─────────┘
                                                  │            └───────────────┐
                                                  ▼                            ▼
                              segment (512 bytes)     segment     segment (last)
                              ┌──────────────┐        ┌────────┐       ┌───────────┐
                              │ next ●       ├───────►│ next ● ├─► … ─►│ null      │
                              │ count        │        │ 62     │       │ 62        │
                              ├──────────────┤        ├────────┤       ├───────────┤
                              │ object 0 ●   │        │ …      │       │ objects   │
                              │ … up to 62   │        │ full   │       │ full      │
                              └──────┼───────┘        └────────┘       └───────────┘
                                     ▼  first, partly filled
heap object (calloc), 8-byte slots
┌─────────┬───────────────────────────────┬────────┬────────┬─────
│ cid i32 │ gc i32                        │ slot 1 │ slot 2 │ …
│         │ normal [0|phys:15|m:1|req:15] │        │        │
│         │ on path [1|cursor:31]         │        │        │
└─────────┴───────────────────────────────┴────────┴────────┴─────
  class instance: itable, fields…      closure: apply, underlying
  array: size, elements…               buffer: bytes, where Addr fields point
```

Static objects, such as string literals, parameter keys and the runtime state,
are in no segment. Their `gc` half is 0, so they count as `phys = 0` and
`req = 0`.

When region `r` collects, the sweep packs the survivors into the front of its
own segments, and the other segments go back to the pool. A merge keeps all
of `r`'s segments. Either way the segments are given to the parent. The partly
filled one tops up the parent's first segment. If objects are left in it, it
becomes the parent's first segment, since the old one is full now. Otherwise
it goes back to the pool. The full ones are spliced in after the parent's
first segment.

```
before a collect

  parent:  first ─► [ P0: 40 ] ─► [ A: 62 ]
  r:       first ─► [ S1: 62 ] ─► [ W: 30 ] ─► [ S3: 0 ]
                     survivors, packed          emptied by the sweep

the survivors given to the parent, the partly filled one first

           [ W: 30 ] ─► [ S1: 62 ]

after

  parent:  first ─► [ W: 8 ] ─► [ S1: 62 ] ─► [ P0: 62 ] ─► [ A: 62 ]
                                               +22 from W
  pool:    [ S3 ] ─► …
```

The parent again has only one partly filled segment, its first one. If `P0`
had room for all of `W`, `W` would go back to the pool, `P0` would stay first,
and `S1` would follow it.

Codegen calls `Regions.enter` at the start of a region function and
`Regions.leave` before each `ret`, passing the result or `null`. At the end of
an iteration of a collecting loop it calls `Regions.collectLoop` when one is
due. All three are `noinline`. The barrier `Regions.barrier` and
`Regions.requested` are ordinary functions, which LLVM inlines.

Usage:

```
jo compile --llvm app.jo -o app
jo compile --llvm --region-policy none --region app.process --region app.step app.jo -o app
jo compile --llvm --mem-profile app.jo -o app && JO_MEMSTATS=1 ./app
```

`--region-policy` opens regions in more functions besides those named by
`--region`: `all` (the default), `scalar` for functions returning a primitive
value other than `Unit`, or `none`. A function that cannot allocate opens no
region under any policy, unless `--region` names it. Under `all`, the result
of a region function is allocated directly in its result region, which
`--place-results` also turns on for the other policies. `--mem-profile` names
the functions in the memory statistics.

The names are full names, as in `--link`. Only top-level functions, defined
directly in a namespace or a section, can be region functions. Class methods
cannot, because `pkg.C.f` could name either a method of class `C` or a function
of section `C`. The driver resolves each name to a symbol before lowering, and
codegen compares symbols. A name that matches no top-level function is an
error, and so is a function of the runtime, which manages the regions and
cannot open one.

Tests: `tests/gc` checks that escaping objects survive, and
`tests/custom/gc-regions` checks that memory is reclaimed. To check for memory
errors, keep the IR with `--keep-ll`, add `sanitize_address` to the attribute
groups and compile it with `clang -fsanitize=address`. With clang 15 on recent
kernels, AddressSanitizer itself needs `setarch -R`.
