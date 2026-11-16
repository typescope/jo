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

Superseded by [Redesign: regions per loop iteration](#redesign-regions-per-loop-iteration).

Jo's tail-call optimization turns a self-recursive function into a loop. A
region function rewritten this way opens its region once, and the loop opens
iteration regions like any other loop (see
[Tail-recursive loops](#tail-recursive-loops)), which free the garbage of
earlier steps.

Merging regions is always safe. It only delays reclamation. The backend
therefore may ignore a region boundary when it is inconvenient, for example
beyond the level limit.

## Object header

Slot 0 of every heap object is `cid: i32 | gc: i32`. The `gc` half has two
forms:

```
normal    [0 | phys: 15 | req: 16]
on path   [1 | cursor: 31]
```

The on-path form is used only during cleanup (see below).

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
jo-mem summary peak-rss-kb=4636 allocated=636566 freed=636553 runtime-region=13 skipped-enters=0
jo-mem to-runtime direct=13 store=0 trace=0 return=0
jo-mem closes free=5 handoff=1014 merge=7500 collect=2082 traced=9065
jo-mem loops iteration-regions=2092
jo-mem hint rebuild with --mem-profile to attribute these to functions
```

- `summary`: the peak RSS in KB, the objects allocated and freed, the
  objects in the runtime region at exit, and the enters skipped at the level
  limit. After an `abort`, the objects of the open regions are neither freed
  nor in the runtime region, so `allocated - freed` exceeds `runtime-region`.
- `to-runtime`: the objects allocated directly in the runtime region, and
  those promoted to it by a store into an object there or an assignment to
  a variable whose home runs there, by the trace of a closing region, and by
  a return to code in the runtime region. These stay
  until the program exits. `direct` counts what code that runs in no region
  allocates: `Core.start`, and the entry function and its callees when they
  open no region. Under the default policy, `direct` and `return` stay near 0.
- `closes`: the closes that freed everything, handed every object to the
  parent (see [The close fast path](#the-close-fast-path)), merged and
  collected, and `traced`, the objects whose `req` the traces of collecting
  closes lowered, besides the escape roots. An object traced at every
  close it passes through counts once per close, so `traced` far above `allocated` means repeated
  tracing (see [Cost](#cost)). The trace counts in a local variable and adds
  it to the total once per collect.
- `loops`: the iteration regions opened, not counting enters skipped at the
  level limit (see [Iteration regions](#iteration-regions)).

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
at the next return of a region function or the next close of an iteration
region, once the state is consistent. The report runs in a region of its
own, so it adds nothing to the promotions or to the runtime region. Its
strings still count in `allocated` and `freed`, but not in `requested`, the
bytes that end a batch of iterations: the report restores the counter, so
iteration regions close at the same iterations with and without
`JO_MEMSTATS`.

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
collects. A region that stays open for a long time therefore holds at most
about 500 merged objects of its callees at once. The region of a loop's
iterations closes at the end of every batch (see
[Iteration regions](#iteration-regions)) and frees them.

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
at the limit, which frees nothing until that frame returns. The close of an
iteration region gives back the levels merged into it while the function is
open (see [Levels of iteration regions](#levels-of-iteration-regions)). At
depth 2000,
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
callee inherit it. A marked call may be indirect, see
[Indirect calls](#indirect-calls). Allocations and calls inside a loop stay
in the current region, since a loop may allocate many values of which only
one is returned.

```
call f, marked:   inherit()          // the next enter takes the result level
                  f(args)
enter:            child.result = inherit > 0 ? inherit - 1 : current.num
marked alloc:     allocResult(size)  // in regions[current.result]
```

A call site passes a level only to a callee that takes it, see
[Passing the result level](#passing-the-result-level) and
[The flag](#the-flag). Past the level limit, the current function opened no
region, and `allocResult` allocates in the current region.

Placement only affects performance. An object in the result region is older
than the current region, so its initializing stores go through the barrier,
except a function reference, like the `apply` of a closure, which is not a
heap object. If a marked value does not escape after all, it stays in the
result region until that region closes.

Placing results is on under `--region-policy all`, and `--place-results` turns
it on for the other policies.

### Functions that only allocate their result

Status: implemented. To be narrowed or dropped, see
[Redesign: regions per loop iteration](#redesign-regions-per-loop-iteration).

A function whose every allocation goes to its result needs no region. Its
region could only hold what it returns, so it opens none. It allocates the
result directly where the result goes. This covers constructors, bridges
that box a value, and small factory functions.

#### The problem

`jo.Range.RangeIterator$next$bridge`, the bridge of `Iterator[Int].next`,
calls `enter`, boxes the `Int` with `boxInt` and calls `leave(box)`. The
values of Sieve go up to 5000, above the shared boxes, so `boxInt`
allocates. The region holds one object, the result, which escapes. The
region of `sieve` holds the boxes of earlier iterations until its loop
collects, so it has more than 8 segments, and every `leave` collects:
`spreadEscapes`, `pack` and `give` for one object. Sieve does 10.6 million
such collects, one per iteration of its `for` loop. In the measurements
below, Sieve takes 2.17 s today and 1.42 s under
`--region-policy scalar`, where the bridge opens no region.

Constructors pay too. A constructor function such as `tree.Inner(children)`
opens a region only to allocate its object with `allocResult` in the result
region, and closes the empty region again. Every `new` through a
constructor function costs an `enter` and a `leave`.

#### The rule

A function places its result without a region when all of these hold:

- the policy would make it a region function, and `--region` does not name
  it
- results are placed, and it returns a pointer other than `Unit`
- every allocation site in its body is placed: each record literal is
  marked by the analysis of [Placing results](#placing-results), and so is
  each call that may allocate

The scan is the one of `Allocations.scala` with the placed sites left out.
As there, a branch that ends in a call that never returns does not count,
so a bounds check or an assertion does not make a constructor a region
function. A marked indirect call is placed too, see
[Indirect calls](#indirect-calls).

A marked call counts as placed whatever its callee. Where its objects go
depends on the callee:

- a callee that opens a region or places its result allocates its result at
  the level passed to it, `l` below
- a runtime function, like `boxInt`, `StringOps.+` or `RefArray.create`,
  never opens a region or places, so it allocates in the current region,
  which is now the caller's region

So the function itself allocates nothing in a region of its own, but its
result can end up in the caller's region rather than at `l`. Today the same
objects land in the function's region and move to the caller's region when
it closes. They end up in the same place, with one close less.

Codegen represents `Unit` as a pointer (`llvmType` returns `void` only for
the void type), so the rule names `Unit` explicitly. A `Unit` result has
nothing to place, and a function that returns `Unit`, like the entry
function or a callback, keeps its region.

Under `scalar` and `none` the rule changes nothing: there a function that
returns a pointer opens a region only when `--region` names it.

#### Passing the result level

Today `inherit` sets a flag, and the next `enter` takes the result level of
the current region instead of the current level (`inherit` and `enter` in
`Regions.jo:324-351`). A function without a region has no `enter`, so the
flag becomes the level itself:

```
State.inherit: Int          0, or the result level + 1 for the next callee

inherit():                  inherit = (skipped > 0 ? level : current.result) + 1
passResult(l):              inherit = l + 1
enter:                      child.result = inherit > 0 ? inherit - 1 : level
                            inherit = 0
resultLevel():              l = inherit > 0 ? inherit - 1 : level
                            inherit = 0
                            return l
allocAt(size, l):           allocResult with an explicit level
```

A function that places without a region calls `resultLevel()` at entry and
keeps `l` in a local. A marked record literal calls `allocAt(size, l)`, with
the barrier on its initializing stores, like `allocResult`. A marked call to
a callee that opens a region or places its result calls `passResult(l)`
right before the call, after the arguments are computed. A region function
keeps calling `inherit()`. No call passes a level to a callee that returns
`Unit`, also not to a region function, since there is nothing to place.
This is a small change from today, where a marked call of a region function
that returns `Unit` calls `inherit`. The real code skips it always. The
prototype skipped it only with the placement of stores, since removed,
turned on.

Past the level limit, `inherit` now passes the current level. Today it
sets the flag and the next `enter`, which is skipped too, ignores it. A
function that places without a region is not skipped, so it would take
`current.result`, the result level of the function that opened the region
at the limit, which is not its caller's. `allocResult` already uses the
current level past the limit for the same reason.

Direct calls keep this invariant: the flag is 0 except between a call that
passes a level and the entry of its callee, which takes it in `enter` or
`resultLevel`. A direct call passes a level only to a callee that takes it.

#### Why it is sound

- The function runs in its caller's region, like a function that cannot
  allocate. Its frame holds pointers into that region or older ones, as the
  invariant of the [Overview](#overview) requires.
- `l` is the caller's level or the caller's result level. Both regions are
  open until the caller returns, so they outlive the call.
- An object allocated at `l` is at least as old as the current region. Its
  initializing stores go through the barrier, as for `allocResult`.
- A marked value that is not returned on some path stays at `l`. Placement
  already accepts this.
- Callees that open a region close into the caller's region. Their merges
  are bounded by the merge budget as before.

#### Loops and statistics

The function opens no region, so its loops open no iteration regions.
Nothing in a loop is placed, so any allocation in a loop makes it a region
function. The loops of a function with no region allocate nothing, and
there is nothing for them to free.

The function gets no number for `--mem-profile`. Its allocations count for
the region they go to. One placed in the runtime region counts in `direct`,
like an object of `allocResult`. Growth reports wait for the next `leave` or
`leaveLoop`, so they may come a little later.

The entry function needs no case. It returns `Unit`, so it never qualifies
and opens its region as before.
A function that does qualify is ordinary under this rule, whoever calls it.

#### Alternative considered: merging a region from which everything escapes

A runtime-only fix for Sieve changes `close` (`Regions.jo:612-630`). When
the budget refuses a merge, the region has one segment, and every object in
it escapes, nothing in it is garbage. `close` then rewrites `phys` of each
object to the parent's level, keeps `req`, passes `min` up and gives the
segment to the parent, without a trace and without using a level. This is
sound for the same reasons as a merge: every `req` is at or below the
parent's highest level, and the parent's close spreads the escapes it
records. It needs about 25 lines.

It removes the collects of Sieve but keeps its `enter` and `leave`:

| Program | Today | Fast path | Result only | `scalar` |
|---|---|---|---|---|
| Sieve | 2.17 s | 1.96 s | 1.48 s | 1.42 s |
| NBody | 1.36 s | 1.37 s | 1.10 s | 1.01 s |

The fast path turns the 10.6 million collects of Sieve into merges and saves
under a third of the gap to `scalar`. The rule above saves all of it, since the
bridge then calls neither `enter` nor `leave`. Times come from the run of
[Measurements of the rule](#measurements-of-the-rule).

The rule above is kept: it is faster on Sieve and NBody, and it also saves
the `enter` and `leave` of every constructor. The fast path was added later
for regions that do open, see [The close fast path](#the-close-fast-path).

#### Changes for the rule

| File | Change | Lines, estimated |
|---|---|---|
| `Regions.jo`, `Platform.jo` | `inherit` as a level, `passResult`, `resultLevel`, `allocAt` | 35 |
| `CodeGen.scala` | `placesWithoutRegion` in `opensRegion`, `resultLevel` at entry, `allocAt`, `passResult` at marked calls, `isUnitType` checks where a level is passed | 55 |
| `Allocations.scala` | the scan with placed sites left out | 15 |
| `LLVMRuntime.scala` | 3 symbols | 3 |

The `closes` lines of the examples in the
[memory guide](../../docs/usage/guides/memory.md), at lines 137, 186, 212
and 257, need new counts, since fewer regions open.

#### Tests of the rule

- `tests/gc/place-bridge.jo`: a `for` over a range above the shared boxes,
  boxing through the bridge, also under `JO_LOOP_STRESS=1`.
- `tests/gc/place-ctor.jo`: a recursive builder through constructors, which
  opens no region at all, called past the level limit, and a constructor
  whose result is dropped.
- `tests/custom/gc-memstats`: `ret.jo` keeps its rows, since `wrap` returns
  a pointer built in a loop.
- `tests/gc/place-unit.jo`: an entry function `main(): Unit = go()` with a
  loop, and an `Array.tabulate` of a closure that returns `Unit`, also
  under `JO_LOOP_STRESS=1`. The IR check of the test expects `enter` in
  `main` and `clearResult` in the closure. It runs in
  `tests/custom/gc-regions`, since `tests/gc` only compares output.
- `tests/pos` and `tests/gc` with AddressSanitizer, also under
  `JO_LOOP_STRESS=1`.

#### Measurements of the rule

Time is the least user time of 5 interleaved runs of the Are We Fast Yet
harness, pinned to one core of an x86-64 machine (load 0.9 to 2.7). The
medians are within 3% of the minimums. The allocator alone can move a
benchmark by 15%: the order in which `calloc` and `free` reuse memory
changes with the place of an object among its neighbors, and with the size
of the per-thread cache of glibc. "Result only" is a prototype of this rule
alone. Peak RSS did not change.

| Program | Today | Result only | `scalar` | Collects today → result only |
|---|---|---|---|---|
| Sieve | 2.17 s | 1.48 s | 1.42 s | 10632000 → 0 |
| NBody | 1.36 s | 1.10 s | 1.01 s | 113582 → 0 |
| Json | 1.55 s | 1.16 s | 0.62 s | 41900 → 41900 |
| Havlak | 2.86 s | 2.64 s | 2.07 s | 2901438 → 784912 |
| Queens | 0.61 s | 0.51 s | 0.46 s | 83 → 83 |

Storage, Richards, DeltaBlue, CD, Permute and the small benchmarks do not
change beyond noise. Functions that return `Unit` keep their regions, so
DeltaBlue keeps the region of `EqualityConstraint$inputsDo`. With `Unit`
excluded, DeltaBlue does 219860 merges and 112900 collects, Havlak 2986659
and 784912, and Bounce 3002 merges, against 395317 and 343818, 4527062 and
2901438, and 3483 today.

#### Risks

- A result built by a runtime function, like a string, lands in the
  caller's region and not in the caller's result region when the call was
  placed. It moves once, at the caller's close.
- The merges of the function's callees go to the caller's region. The merge
  budget bounds them to 8 segments.

#### Not covered: functions that only box

Permute, Queens and Sieve spend time in functions that open a region only
because they may box an `Int` into a generic array, and in the loop checks
of their callers. In Permute, `swap` boxes values below 6, which are shared
boxes, so its region is always empty. Under `scalar`, Permute takes
0.23 s instead of 0.46 s. Telling these apart needs knowledge of
the values, so it is left for later.

### Indirect calls

Status: implemented.

A marked indirect call whose type returns a pointer other than `Unit` passes
the result level with `inherit` or `passResult` (see
[Passing the result level](#passing-the-result-level)). The callee is
unknown. If it opens a region or places its result, it takes the level.
Every other function whose address is taken, a closure body, an interface
method or a bridge, sets the flag to 0 at its entry, whatever it returns.
The type of the call does not tell what the callee returns: a closure that
returns `Unit`, called through a generic `T => S`, is a call that returns a
pointer. Like any marked call, an indirect call inside a loop is not
marked.

A marked indirect call counts as placed for
[Functions that only allocate their result](#functions-that-only-allocate-their-result).
So a function whose result comes from a closure or an interface method,
like `TaskControlBlock$runTask` of Richards, opens no region. Removing this
in step 5 of the redesign made `runTask` open a region again, and Richards
took 1.33 s instead of 1.05 s, so it was kept.

#### The flag

The invariant of the flag is: it is 0 except between a call that passes a
level and the entry of its callee. Every function that a
call can pass a level to takes the level or clears it first thing:

- a region function takes it in `enter`, also past the level limit
- a function that places without a region takes it in `resultLevel`
- a direct call passes a level only to one of these two
- an indirect call can only reach a function whose address is taken, and
  such a function that is neither of the two clears the flag at entry

So no later `enter` ever sees a level meant for another call. Clearing
after the call returns would not be enough: a callee that ignores the flag
could call a region function, which would take the level. The clear costs
one store in closures and interface methods that cannot allocate. Codegen
finds the functions whose address is taken by a scan of all bodies before
it emits any: function references outside the callee position of a call,
and the entries of the interface tables it emits. The clear is emitted only
when results are placed, since otherwise no call passes a level.

### Placing what is stored into the result

Status: removed in step 5 of
[Redesign: regions per loop iteration](#redesign-regions-per-loop-iteration).

This placed a value that a loop stored right away into the result, like
`arr[i] = values(i)` in `Array.tabulate`, in the result region. Element
stores counted as stores: `Array.setUnchecked`, and any function that always
passes two of its parameters to an element store. A store through fields of
a variable, `x.f.g = v`, counted for the variable. Inside a loop, the stored
expression and the object of a stored `new` were placed. With
[Indirect calls](#indirect-calls), a tree built through `Array.tabulate` or
`List.map` was allocated where the outermost caller kept it. The commit that
added it, "LLVM: place stored results and open no region for functions that
only allocate their result" (1d36c443), and 2acce204, 48530586 and
006dcac9 after it have the full design.

It was removed for two reasons. It changed which memory a program keeps: a
value stored into a slot of an array the runtime created, and overwritten
later in the same call, stayed in the result region until that region
closed. A loop that overwrites the one slot of the array it returns a
million times, 5 times over, kept 79 MB with the rule and keeps 40 MB
without it. And whether a store counted depended on the shape of the
wrapper functions around it, which a programmer cannot see.

The cost, measured against step 4 as the median user time of 9 interleaved
runs pinned to one core: Storage takes 5.8 s instead of 1.48 s, about 4
times, and traces 88.4 million objects instead of none. A tree of depth
8 built 200 times through `tabulate` takes 2.1 s instead of 0.60 s, and
traces 51.8 million objects for 7.6 million allocated. Trees built through
`List.map` or `a[i] = build(...)` in a loop are traced again the same way.
Richards, Json, Havlak, DeltaBlue and Permute do not change beyond noise.
The memory guide shows how to restructure such a tree: pass a fixed number
of children to the constructor, or build with `--region-policy none` and a
`--region` for the caller.

## Collecting a region in a loop

Status: removed. [Redesign: regions per loop iteration](#redesign-regions-per-loop-iteration)
replaced it.

Loop collection collected the region of a function at the end of a loop
iteration, once the program had requested `max(kept, 1 MB)` bytes since the
last collection. It marked from the variables of the frame that later code
could read and from the escape roots, and freed the rest in place. It needed
the roots of the frame, a rewrite of the operands computed before a loop, a
mark bit in the object header, a table of object sizes and stress modes
under `JO_LOOP_COLLECT`. Which garbage a loop held depended on a liveness
analysis of the frame, which a programmer cannot predict and
[The principle](#the-principle) rules out. The commit that
added it, "LLVM: collect a region at the end of a loop iteration", has the
full design.

Without it, a value replaced in a variable declared outside a loop, or in a
field of an older object, stays until the region of that variable or object
closes (see [The rules for programmers](#the-rules-for-programmers)).

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
region if it may allocate, and its loops open iteration regions. Under
`none` or `scalar` it runs in the runtime region, unless `--region` names
it.

This is sound. `Core.start` allocates the argument array and the context
entries of `IO.stdout`, `IO.stderr` and `IO.args` before the call, so they
are in the runtime region, older than the entry's region. Its frame holds
pointers only into level 0, as the invariant requires. A store into one of
these objects runs the barrier and makes an escape root. Static data has
`phys = 0`, below any region `enter` opens.

The cost is one close when the entry returns, before `finish`, which frees
what its region still holds object by object.

The runtime region then holds what the runtime sets up before the entry
function, and what is stored into it later. A program can store only into
one of those objects: the array of `IO.args`.

## Redesign: regions per loop iteration

Status: iteration regions are implemented (step 3), in batches of 8 KB.
Loop collection is removed (step 4). Placement in loops is removed (step
5). It supersedes
[Collecting a region in a loop](#collecting-a-region-in-a-loop) and
[Placing what is stored into the result](#placing-what-is-stored-into-the-result).

### The principle

The rules for memory must be simple and predictable, so that programmers
and LLMs can shape code to them. Heuristics may change how long a program
takes, never which memory it keeps. The memory guide states the rules and
is the contract.

Three mechanisms broke this. Loop collection, now removed, freed a region
when the bytes requested since the last collection reached a threshold, so
which garbage a loop held depended on a counter. Placement in a loop, now
removed, changed which values stay: a value stored into a slot of a
runtime-created array and overwritten later stayed in the result region. A
function that only allocates its result runs its runtime calls in the
caller's region, so their temporaries stay there. The first two also needed
much machinery: frame roots, the operand rewrite, marking and element-store
detection.

### The rules for programmers

1. A call frees what it allocated when it returns. It keeps what it returns
   or stores into an older object.
2. A loop frees what its iterations allocated, a bounded amount at a time.
   Each run of the loop works in batches of 8 KB. A batch ends at the end
   of the first iteration that brings the bytes it requested to 8 KB. The
   end of every batch but the first frees what the batch allocated, except
   what it assigned to a variable declared outside the loop or stored into
   an older object. What the first batch of a run allocated stays in the
   region around the loop, that of the function or of an outer loop's
   iterations, until that region closes.

The bound is in bytes, so it does not depend on the size of objects. An
iteration that allocates a large buffer ends its batch alone. If that batch
is not the first of the run, the end of the iteration frees the buffer. If
it is, the buffer stays until the region around the loop closes. A loop
that allocates a 40 MB buffer in its first iteration keeps those 40 MB,
usually until its function returns. To free such a buffer at once, allocate
and use it in a function that does not return it. Rule 1 then frees it when
the function returns.

The first batch stays because the region of the iterations opens at its
end, not when the loop starts. Opening it when the loop starts costs 8% on
Permute and 20% to 26% on Havlak, which run many short loops (see
[Batching iterations](#batching-iterations)).

Limitation: a value replaced in a variable declared outside a loop, or in an
older object, is freed only when the region of the variable or the object
closes: at the next close of the iterations that declare it, or at the
return of its function. In a function that never returns, such values are
never freed. This is deliberate for now. There is no explicit collection
call and no automatic loop collection.

Rule 1 holds up to the merge budget. A function region that keeps something
may merge into its caller's region, which then holds up to 8 segments,
about 500 objects, of the callee's garbage until it closes or collects (see
[When to merge](#when-to-merge)). The region of a loop never merges (see
[Closing an iteration region](#closing-an-iteration-region)), so rule 2
holds up to its bound of bytes.

### Iteration regions

The iterations of one run of a loop share a region, the iteration region.
The iteration covers the condition and the body, so what the condition
allocates is freed too. At the end of an iteration, the loop compares the
bytes the program requested since the region opened with the bound. Below
the bound, the next iteration runs in the same region. At the bound, the
region closes and a new one opens. Every exit of the loop closes it.

The region opens at the first iteration end that reaches the bound, not at
the start of the loop. Until then the iterations run in the region current
before the loop, so a run of the loop that allocates less than the bound
opens no region and costs one load and one compare per iteration. Codegen
lowers such a `While` to:

```
              level = Regions.level(); open = false
              next = Regions.loopNext()
while.cond:   c = cond
              br c, while.body, while.exit
while.body:   body
              br requested >= next, while.cycle, while.cond
while.cycle:  if open: leaveLoop()
              level = enterLoop(); open = true
              next = Regions.loopNext()
              br while.cond
while.exit:   if open: leaveLoop()
              br while.end
```

`requested` is the counter of bytes that every allocation increments.
`loopNext` returns it plus the bound, 8 KB, or plus nothing when the
environment variable `JO_LOOP_STRESS` is set to a value other than the
empty string and `0`. The stress mode thus closes the region at the end of
every iteration, which the tests use to find premature frees. `level` is
the level of the current region, `enterLoop` the level it opened.

`enterLoop` is `enter` for the iterations. It copies the result level and
the function id of the enclosing region, so `allocResult` and the
statistics see the function. It does not read the passed result level: a
call sets it right before the call (`passResult` in `compile(app)`), so it
is never pending at an iteration end. Past the level limit it counts a
skip, like `enter`, and returns the current level. `leaveLoop` closes the
region without merging it (see
[Closing an iteration region](#closing-an-iteration-region)). It is a
separate entry point rather than `leave(null)`, so the rule that the region
never merges does not need a flag in the descriptor.

Every other way out of an iteration is a `Return` in the lowered tree, since
`break`, `continue` and the step of a tail-recursive loop all become returns
to a label (`transformBreak`, `transformContinue` in `Namer.scala`,
`replaceTailCalls` in `TailCallOpt.scala`). Codegen records the number of
running loops at each `Labeled` and closes the region of every loop between
the `Return` and its target, if it opened one:

- `continue` targets a `Labeled` around the body, at the depth of the body,
  so it leaves nothing and falls into the normal end of the iteration.
- `break` targets a `Labeled` around the `While`, so it closes one region.
- The step of a tail-recursive loop targets a `Labeled` inside the body,
  like `continue`.
- A labeled return carries no value: codegen asserts a void `Labeled`, so
  these leaves keep nothing.
- A return from the function promotes its result to the level of the
  function's region first, then closes the region of every running loop,
  innermost first, then runs the function's `leave(result)` as before. So
  the result escapes each iteration region it passes.

A call that never returns needs no leave: `abort` exits the process and Jo
has no exceptions.

The cost per iteration is one load of `requested` and one compare, inline,
plus one compare per assignment of the next section. A run of a loop costs
two calls of small functions that LLVM inlines and one test at its exit.
Only a close calls the runtime.

### Batching iterations

The first implementation opened a region per iteration: `enterLoop` before
the condition and `leaveLoop` at every iteration end. It freed exactly what
each iteration allocated, and it was too slow. Median user time of 5
interleaved runs against the commit before:

| Program | Before | Region per iteration |
|---|---|---|
| Permute | 0.48 s | 0.60 s (+25%) |
| Tight loop, one dead object per iteration | 0.45 s | 0.55 s (+22%) |
| `l = Cons(i, l)`, 4M nodes | 0.41 s | 0.54 s (+32%) |
| `s = s + "ab"`, 2M steps | 0.11 s | 0.16 s (+45%) |

Permute's loops allocate nothing, but they call region functions, so they
count as loops that may allocate. Each of its 8.66M iterations paid an
`enterLoop` and a `leaveLoop` on an empty region, about 15 ns, which is a
chain of loads and stores through the descriptor and the state. Making the
two functions inlinable did not help. Loops that keep an object per
iteration also paid a hand-off per iteration: a segment from the pool, a
scan, a move of the object and the segment back. Fast paths in `leaveLoop`
for one segment cut that cost by about half, not below 10%.

Batching pays these costs once per 8 KB. Opening the region only at the
first close also removes the region of a run that allocates less than the
bound. Opening it when the run starts would free the first batch too, so
rule 2 would have no exception. It was measured: `enterLoop` before the
condition, and an unconditional `leaveLoop` at each close and exit. Median
user time of 9 interleaved runs, pinned to one core:

| Program | Open at the first close | Open at the start |
|---|---|---|
| Permute | 0.48 s | 0.52 s (+8%) |
| Storage | 1.61 s | 1.70 s (+6%) |
| Havlak | 2.09 s | 2.64 s (+26%) |
| Json, Sieve, Richards, DeltaBlue | 1.28, 1.24, 1.03, 0.28 s | the same |
| Tight loop, hot loop, `l = Cons(i, l)` | 0.40, 4.44, 0.65 s | 0.40, 4.21, 0.65 s |

A second run gave Permute +9%, Storage +0% and Havlak +20%. Making
`enterLoop` and `leaveLoop` inlinable gave the same times. Permute runs its loops 3.6M times, each a few iterations long,
and Havlak runs many short loops over small collections. Each run pays an
open and a close of an empty region. The first batch is at most 8 KB plus
one iteration, so the lazy open stays.

The bound is chosen by measurement. Median user time of 5 runs:

| Program | Before | 4 KB | 8 KB | 16 KB | 32 KB | 64 KB |
|---|---|---|---|---|---|---|
| Tight loop | 0.45 s | 0.40 s | 0.45 s | 0.41 s | 0.42 s | 0.45 s |
| `l = Cons(i, l)` | 0.43 s | 0.46 s | 0.47 s | 0.50 s | 0.54 s | 0.49 s |
| `s = s + "ab"` | 0.11 s | 0.12 s | 0.12 s | 0.13 s | 0.17 s | 0.16 s |

A close of a region whose objects all escape is a hand-off if the region
has at most 8 segments (see [The close fast path](#the-close-fast-path)),
and a collect otherwise. 8 segments hold 496 objects, 15.9 KB of 32-byte
objects, so with a bound up to 8 KB a batch of small objects that all
escape is handed off without a trace. Above 16 KB, the closes of the
append and cons loops collect, which costs more per object. Below 8 KB,
times do not improve, and the cost per close grows. 8 KB is the largest
bound with hand-offs, so it holds the garbage of a loop to 8 KB plus one
iteration.

The cost that remains is in loops that keep what they allocate: each
object is allocated in the iteration region and moved to the parent at
the close. Without loop collection, the cons loop takes 0.27 s with
iteration regions against 0.23 s before.

### Which loops open regions

A loop opens iteration regions when both hold:

1. Its function opens a region (`opensRegion` in `CodeGen.scala`). This keeps
   the runtime out, whose functions never open one and manage the regions
   themselves. Under `--region-policy none`, only the loops of functions
   named with `--region` open iteration regions. Under the default policy
   every function with a loop that may allocate opens a region once
   placement in loops is gone (step 5). Until then, a function whose loop
   allocates only placed values places its result without a region
   (`onlyPlaced`), and its loops open none.
2. The condition or the body may allocate, by `mayAllocate(word)` of
   `Allocations.scala` on the `While`. A branch that ends in a call that
   never returns does not count, and an indirect call does.

A loop that cannot allocate would never reach its bound, so codegen leaves
out even the compare.

A loop that opens no region is not a home. Its assignments to variables
declared outside it, and the variables declared in it, use the nearest
enclosing loop that opens regions as their home, or the function if there
is none (see the next section).

### Assignments to variables declared outside the loop

Every variable belongs to a home: the function's region, or the iteration
region of a loop. A variable belongs to the innermost region-opening loop
whose condition or body contains all of its occurrences and writes it
before it reads it in every iteration (see the check below). Parameters and
all other variables belong to the function.

The rule does not use `isDefine`. Most phases emit plain `Assign`s for the
variables they create: the binders and temporaries of `PatternMatcher`,
which also binds the variable of `for x in xs`, the arguments and contexts
of `LowerContextParams`, and the temporaries of `MaterializeView` and
`Erasure`. Each of them writes its variable before it reads it.

Writing before reading is a soundness condition: a read before the write
would see a value of an earlier iteration, which a close between the two
iterations may have freed. `IterationRegions.scala` checks it with one walk
of the loop's condition and body in evaluation order, keeping the set of
variables written so far, like the definite assignment of Java:

- A `Bool` word has two sets: written when it is true, and written when it
  is false. Any other word has one set, which serves as both.
- A literal `true` has the running set when true and all variables when
  false, since that outcome cannot happen. A literal `false` is the
  reverse.
- A `Block` walks its earlier words into the running set, then takes the
  two sets of its last word. `Encoded`, which `PatternMatcher` wraps around
  values, is transparent.
- The frontend types `a && b` and `a || b` as calls of `Bool_and` and
  `Bool_or`, and `compile(app)` in codegen turns them into `If(a, b, false)`
  and `If(a, true, b)`. The walk treats them the same way. `!` needs no
  rule, since the frontend binds no pattern variable under it.
- `If(c, t, e)` walks `t` with the when-true set of `c` and `e` with the
  when-false set. Its sets are the intersections of those of its branches.
- A `Return` and a call that never returns, like the failure of a match,
  have all variables as their set, since the code after them cannot run.
  So a branch that cannot complete drops out of the intersection. This also
  covers an `If` whose branches all cannot complete, and dead code after a
  `Return`.
- A nested `While` and a `Labeled` are walked with the set at their entry
  and leave that set, since their body may run zero times or be left early.
- The argument of `paramKey` is a name, not a read.
- A read of the variable outside the set fails the check.

The sets make pattern binders pass. `case Some(v) => body` lowers to
`If(test, body, next)`, where `test` is `If(classTest, Block(v = ..., true),
false)`. `v` is in the when-true set of `test` but not in its when-false
set, so the read in `body` passes. The same holds for
`while cur is Some(node)`, `opt is Some(v) && v > 0`, `for k ~ v in m` and
a refutable `val` pattern.

So the rule is: a variable belongs to the innermost region-opening loop that
contains all of its occurrences and passes the check, or to the function if
no such loop does. A variable that fails at one loop is checked again at
the next enclosing one, since that loop may also read it before writing it
in an iteration. Falling back is sound and only keeps more. The check runs
on the inner loops first, with all variables whose next candidate is the
loop at once.

An assignment `v = x` in the condition or body of a loop to a variable
whose home is outside that loop, and whose slot holds a heap reference
(`isHeapSlot`), promotes `x` like the barrier promotes a stored value:

```
require(x, L):   if x != null and x.req > L:
                   x.req = L
                   regions[x.phys].min = min(regions[x.phys].min, L)
```

`L` is the level `v`'s home runs at, which is open. A function keeps the
level `enter` returned in a slot, `%region.level`. A loop keeps the level
its iterations run at in a slot: the current level at the start of the run,
and the level `enterLoop` returned after each close. `enter` and
`enterLoop` return the current level when they skip at the limit. `x.req`
may be a stale level merged into the home. Then `require` lowers it to the
home's `num` without need, which is safe (see [Levels](#levels)). LLVM
inlines `require`, so an assignment costs a compare when the value needs no
promotion.

The barrier is complete because codegen sees every write to a mutable
variable. A lambda or a local function cannot capture one (`checkCapture` in
`Checker.scala`, and
`docs/language/definitions/value-definitions.md`), so no other function
writes it. A variable that no loop writes needs no barrier. The
assignments that rebind context parameters in a tail-recursive loop are
`Assign`s too. The one `Assign` that is not a local write, to the holder of
a singleton, occurs only in the synthesized `objectInitProc`, which has no
loop and runs once at startup in the runtime region.

Values computed before the loop and held in LLVM registers while the loop
runs are in an older region, so the closes of the loop's iteration regions
do not free them.

### Nested loops

An inner iteration region is a child of the outer one, or of the region the
outer iterations run in before their region opens. Its assignments to
variables of the outer iterations use the level in the outer loop's slot,
and those of the function's variables use the function's level. An inner
loop closes its region at every exit, so no inner region is open when an
outer iteration ends. The outer slot changes only at an outer iteration
end, so it is the right level whenever an inner loop runs. Leaving several
loops at once, by `return`, closes their regions innermost first.

### Closing an iteration region

An iteration region never merges into its parent when the budget allows,
unlike a function region. Its parent is the function's region or an outer
iteration region, which stays open for the whole loop, so merged garbage
would stay until the loop ends and rule 2 would not hold. It closes like
this:

| At close | Action |
|---|---|
| Nothing escapes | Free all objects |
| Every object escapes and the region has at most 8 segments | Hand the region to the parent (see [The close fast path](#the-close-fast-path)) |
| Otherwise | Collect |

`leaveLoop` tests the first two cases directly when the region has one
segment and nothing merged into it, the common case for small batches.
`leave` likewise skips the close of an
empty region inline, so `close`, which now has two callers, stays out of
line without a cost for the many empty regions of Permute.

A callee region still merges into the iteration region while the budget
allows. The iteration region frees that garbage, or collects it, at its own
close.

### Levels of iteration regions

An open iteration region takes one level, and its close gives back that
level and every level merged into it. An iteration region never merges, so
it never uses a level of its parent. A frame therefore needs one more level
per nested loop that runs with an open region. A loop that calls region
functions whose results it keeps merges them into the iteration region,
whose next close gives the levels back.

Past the level limit, `enterLoop` skips like `enter`, the matching
`leaveLoop` undoes the skip, and the returned level is the current one. The
iterations then run in the region at the limit, which frees nothing until
it closes, as for calls today.

### Placement in iteration regions

Allocations and calls in a loop are not placed, so no code in an iteration
region reads its result level. `enterLoop` still copies the result level of
its function, so the field holds a level that is open.

### Tail-recursive loops

`TailCallOpt` turns a self-recursive function into
`while _tco_continue do _tco_loop: body`, and the step is
`_p = arg; ...; _tco_continue = true; return to _tco_loop`. The loop opens
iteration regions like any other. The next arguments are assigned to the
parameter copies `_p`, which are assigned before the loop, so they belong to
the function and the barrier promotes the arguments to the function's
region. A result that falls through is assigned to `_tco_result` in the
body and read after the loop, so it belongs to the function too. The
garbage of the steps is freed in batches. The arguments a step replaces
stay in the function's region until the function returns, like any value
replaced in a variable declared outside a loop.

### Why iteration regions are sound

The invariant of the [Overview](#overview) is: a frame only holds pointers
into its own region or an ancestor. With iteration regions it becomes: a
variable only holds pointers into the region its home runs in or an
ancestor, and a register only holds pointers into the region that was
current when it was computed, or an ancestor.

It holds at every step:

- A new object goes to the current region, which is the one the variables
  defined there run in. A call's result is promoted to the caller's
  current region by `leave`.
- An assignment to a variable of an older home runs `require` with the
  level in the home's slot.
- A store into an older object runs the barrier, which records the escape at
  the object's region.

When iteration region `I` closes, at an iteration end or at an exit:

- No region younger than `I` is open. Every call of the iteration returned,
  and every inner loop closed its region at its exit.
- No other frame runs in `I`. Older frames hold no pointer into `I`, which is
  younger than all their regions.
- `I` holds the objects of every iteration since it opened, not only the
  last one. The variables whose home is `I` are dead at every iteration
  end: the check ensures that every iteration writes them before it reads
  them. So no variable refers to an object of an earlier iteration through
  them, and the earlier iterations reach the rest of the program only as
  the last one does.
- Every other variable has its home older than `I`, so what it refers to has
  `req` below `I.num`. It is an escape root of `I`, and `min` records it.
- A register holds a value computed before the loop, in an older region,
  or one computed in the same iteration. A value computed in an iteration
  leaves it only through a variable, a heap store or the function's result,
  which is promoted before the leaves. A `While` and a `Labeled` have no
  value, and the slot of an `if` is read right after its branches.
- Objects of older regions reach `I` only through stores, whose barrier made
  escape roots.

So the escape roots of `I` are complete, and freeing, the fast path and
collecting keep everything reachable from them, as for a function region.

Before the region opens, the iterations run in the region current at the
start of the loop. That region outlives the loop, so it is sound like any
code that runs in it. The level slot holds its level, so `require` promotes
to it, which changes nothing for objects already there.

### Measurements of iteration regions

Median user time of 5 interleaved runs, pinned to one core, at a load of
1.1 to 2.2, against the commit before (2249cdc1), with loop collection
still on. The cons and append loops were measured again with 9 runs.
"No loop collection" builds both with `--no-loop-collect`, which shows the
state after the next step.

| Program | Before | After | Peak RSS before / after |
|---|---|---|---|
| Permute | 0.48 s | 0.45 s | 1.9 / 1.9 MB |
| Hot loop: two small objects and two region calls per iteration | 2.01 s | 2.01 s | 2.4 / 1.8 MB |
| Tight loop, one dead object per iteration, 10M | 0.44 s | 0.43 s | 3.2 / 1.8 MB |
| Same, no loop collection | 0.60 s | 0.41 s | 387.9 / 1.8 MB |
| `l = Cons(i, l)`, 4M nodes kept | 0.40 s | 0.44 s | 12.2 / 12.2 MB |
| Same, no loop collection | 0.26 s | 0.28 s | 12.2 / 12.2 MB |
| `s = s + "ab"`, 2M steps | 0.11 s | 0.12 s | 9.0 / 9.0 MB |
| Same, no loop collection | 0.21 s | 0.23 s | 149.5 / 149.5 MB |
| `s = "ab" + s`, 10000 steps | 0.01 s | 0.01 s | 2.9 / 3.0 MB |
| Same, no loop collection | 0.03 s | 0.03 s | 98.1 / 98.1 MB |
| Storage | 1.62 s | 1.58 s | 2.4 / 2.5 MB |
| Sieve | 1.52 s | 1.24 s | 2.0 / 1.9 MB |
| Json | 1.28 s | 1.31 s | 5.4 / 5.4 MB |
| Havlak | 2.78 s | 2.24 s | 15.9 / 14.5 MB |
| DeltaBlue | 0.31 s | 0.29 s | 11.4 / 10.5 MB |

The tight loop frees its garbage without loop collection: 1.8 MB instead
of 388 MB. The append and prepend loops keep what they replace in `s`, as
the limitation says, so their memory without loop collection does not
change. Sieve and Havlak are faster. This was not broken down. The likely
cause is that `leave` now skips the call of `close` for an empty region
(see [Closing an iteration region](#closing-an-iteration-region)).

Permute's closes change from 783 hand-offs, 1219 merges and 1 collect to
2005 merges and 11 collects. The regions of `benchmark`, which store a new
array into the benchmark object, now merge into the iteration region of the
loop of `innerLoop` instead of its function region. That region closes about
every 8 KB, so it stays under the merge budget, where the function region
filled up and then took a hand-off per close. Its 11 closes collect, since
they also hold the garbage merged from those regions.

Tests:

- `tests/gc/loop-iterations.jo` covers a list built through a variable of
  the function, a variable of an outer iteration assigned in an inner loop,
  `while cur is Some(node)`, a return from two loops deep, `break` and
  `continue`, `match` in the body, `for k ~ v in m` and a tail-recursive
  function. Under `JO_LOOP_STRESS=1` every iteration end closes the
  region.
- `tests/custom/gc-regions` runs `iterations.jo`, a tight loop that drops
  20 million objects, under a limit of 100 MB of
  address space, and checks that its loop closes more than 1000 iteration
  regions. It also checks that `churn` opens no iteration region under
  `--region-policy none`.
- `tests/custom/gc-memstats` reads the loops line with its new field.
  `direct.jo` now prints 5 growth reports instead of 3: `leaveLoop` prints
  the reports that are due, and the region of the iterations closes every
  8 KB, so each doubling of the region of `main` gets its report.
- In `close-handoff.jo`, the regions of `step` now merge into the region of
  the iterations of the loop of `fill`, which stays under the budget. That
  region is handed over at its closes. The hand-offs fall from 3283 to
  1014, so `gc-regions` expects at least 1000.

All tests in `tests/gc` passed in the 4 modes: normal and AddressSanitizer,
with the stress mode of the time off and on. Without the `require` of
assignments to variables declared outside a loop, ASan reports
heap-use-after-free in 16 of the 27 tests in both modes, among them
`loop-iterations.jo`, `loop-vars.jo`, `loop-nested.jo`, `loop-strings.jo`,
`loop-tailrec.jo`, `loop-escape.jo`, `escapes.jo` and `shared-boxes.jo`,
and in `loop-map.jo` under the stress mode.

### The close fast path

Status: implemented in `close`, `allEscape` and `handOff` of `Regions.jo`,
for regions of at most 8 segments. `handoff` in the `closes` line of the
memory statistics counts it.

A region whose objects all escape is handed over instead of collected.
When the merge budget refuses a merge, a close of a small region tries the
fast path before it collects. If every object of region `r` already
escapes, nothing in `r` is garbage. The close then hands `r` to its parent
`p`:

```
close r into p:
  if nothing escapes:                free all
  else if r is a function region and the budget allows: merge
  else if r has at most 8 segments and every o in r has o.req < r.num:
    for every o in r, oldest first:
      o.phys = p.num                   // req stays
      append o to p                    // p.hi stays
    p.min = min(p.min, m)              // m: the lowest promotion of r
  else: collect
```

The objects join the parent oldest first, as if they had been allocated
there. The segments of `r` go back to the pool one by one as they empty,
and the parent takes them for its new segments. Splicing the segments of
`r` into the parent would skip the copy of the pointers, but it breaks the
order of allocation, which costs more later. See
[Order of the objects in a hand-off](#order-of-the-objects-in-a-hand-off).

The test scans the index of `r` and stops at the first object with
`req >= r.num`. A failed test costs at most one scan, which the collect's
pass 1 does anyway. The fast path is tried after a merge, so a close that
merges pays no scan. Trying it first would save the level a merge uses, but
it costs Json about 4% (see the measurements below).

The fast path holds no garbage, but it still needs a bound on the size of
`r`, the merge budget of 8 segments. A handed object stays in the parent,
so the close of every ancestor scans it again. In non-tail recursion where
every level stores a new object into an array of the caller, each region
gets the objects of all levels below it, and the scans take time quadratic
in the depth. Without the bound, 5 rounds of depth 20000 take 6.0 s, against
0.07 s before the fast path. With the bound, a region over the budget
collects, as before, and moves the objects directly to the region owning
their level. The same program then takes 0.09 s, with 200 collects.

It is sound for the reasons a merge is:

- Every `req` in `r` is below `r.num`, which is `p.hi + 1`. `p.hi` cannot
  grow while `r` is open, since only the close of a child of `p` raises it
  and `r` is that child. So every `req` is at most `p.hi`, and `phys = p.num`
  puts the object in `p`'s range.
- No object outside `r` has a level in `r`'s range, since younger regions
  are closed and a barrier never lowers an object to a younger level. After
  the rewrite no object has one either, so `p.hi` stays and the levels of `r`
  are free for the next `enter`.
- An object that also escapes `p` has `req < p.num`. Its promotion was
  recorded in `r`'s range, so `m <= req`, and passing `m` up keeps the
  invariant of [Knowing that nothing escapes](#knowing-that-nothing-escapes).
- The test checks every object of `r`, so every object moves, whatever it
  points to. Objects of `r` that an escaping object reaches are not lowered
  with it: the barrier, `require` and `leave` lower only the object they
  are given, and spreading waits for a collect. So the fast path only
  applies when every object was promoted on its own, like the flat results
  of Sieve. A tree that an iteration keeps fails the test, since only its
  root was promoted, and the region collects. After the fast path, the
  spreading is deferred to the close of `p` that collects, as after a
  merge.

The fast path is the alternative sketched in
[Alternative considered](#alternative-considered-merging-a-region-from-which-everything-escapes),
without its limit to one segment.

After a collect, an object required by a region older than the parent goes
directly to the region owning its level. The fast path leaves it in the
parent and passes `min` up, so the parent's close sees the escape and
cannot free everything. It hands the object over or collects instead. The
same objects are kept. DeltaBlue shows it once: one close that freed
everything before hands its region over now.

#### Measurements of the fast path

Time is the median user time of 5 interleaved runs of the Are We Fast Yet
harness on the LLVM backend, pinned to one core, at a load of 1.2 to 1.9.
"Merge first" is the order kept: merge while the budget allows, then try
the fast path, then collect. "Fast path first" tries it before a merge.
Both orders have the bound of 8 segments. Closes are counted by
`JO_MEMSTATS` with merge first.

| Program | Before | Merge first | Fast path first | Merges and collects before | Handoffs, merges and collects after |
|---|---|---|---|---|---|
| Permute | 0.49 s | 0.51 s | 0.48 s | 1219, 784 | 783, 1219, 1 |
| Towers | 0.18 s | 0.16 s | 0.18 s | 1230, 573 | 572, 1230, 1 |
| Queens | 0.63 s | 0.50 s | 0.53 s | 12922, 83 | 0, 12922, 83 |
| Storage | 1.73 s | 1.53 s | 1.54 s | 5429002, 32000 | 0, 5429002, 32000 |
| CD | 0.50 s | 0.51 s | 0.51 s | 1181115, 85766 | 0, 1181115, 85766 |
| DeltaBlue | 0.30 s | 0.30 s | 0.30 s | 219860, 112899 | 42173, 219860, 70727 |
| Havlak | 2.79 s | 2.63 s | 2.61 s | 2985106, 784911 | 158928, 2985106, 625983 |
| Json | 1.27 s | 1.29 s | 1.48 s | 4320602, 41900 | 0, 4320602, 41900 |
| Sieve | 1.56 s | 1.54 s | 1.51 s | 2, 0 | 0, 2, 0 |
| NBody | 1.09 s | 1.07 s | 1.09 s | 3, 0 | 0, 3, 0 |

Single runs vary a lot: the minimums of Queens are 0.49, 0.52 and 0.48 s,
and those of Json 1.26, 1.27 and 1.26 s. 9 more runs of Json give medians
of 1.29, 1.28 and 1.34 s (before, merge first, fast path first). Two
earlier runs without the bound, at a load up to 3.8, gave Json 1.43, 1.38
and 1.53 s and 1.37, 1.37 and 1.39 s, and Havlak 2.90, 2.86 and 2.76 s and
2.90, 2.93 and 2.74 s, in the same order. Bounce, List,
Mandelbrot and Richards do not change. Peak RSS stays within 0.2 MB, and
`traced` does not change: the collects that the fast path replaces lowered
only their escape roots.

The fast path removes all but one collect of Permute (784 to 1) and Towers
(573 to 1), 37% of those of DeltaBlue and 20% of those of Havlak, in both
orders. Havlak is about 5% faster. With the fast path first, Json is about
4% slower in every run, since 157501 of its merges become a scan and a
rewrite. Merge first does not pay that scan, and it states the fast path
simply: a region whose objects all escape is handed over instead of
collected. Iteration regions never merge, so for them the order does not
matter.

Sieve and NBody no longer collect at all, since
[Functions that only allocate their result](#functions-that-only-allocate-their-result)
gives their bridges and constructors no region. The fast path saves them
nothing now. The numbers of
[Alternative considered](#alternative-considered-merging-a-region-from-which-everything-escapes)
were taken before that rule.

Tests:

- `tests/gc/close-handoff.jo` hands over the regions of a function that
  only stores a box into an array of its caller, and of a caller of it. In
  `pair`, two objects escape to different levels, one reaching the other,
  so the close of the parent must trace from it. In `keep`, a handed box is
  stored into an older array, so the barrier must record the promotion at
  the parent's level, which needs the rewrite of `phys`. The parents of
  `pair` and `keep` allocate about 600 objects first, so the budget refuses
  the merge.
- `tests/gc/close-no-handoff.jo` keeps trees from regions whose roots alone
  escape, so the fast path must not apply.
- `tests/gc/close-deep.jo` recurses 5000 levels deep, each storing a box
  into an array of `main`.
- `tests/custom/gc-regions` checks that `close-handoff.jo` hands over at
  least 1000 regions, that `close-no-handoff.jo` hands over none, and that
  `close-deep.jo` collects.

All pass with AddressSanitizer under the stress mode. Each test catches
a broken runtime. Without the rewrite of `phys`, `close-handoff.jo` prints
wrong output or ASan reports errors. Without the test of `req` in
`allEscape`, `close-no-handoff.jo` fails in every mode. Without the bound
on the size, `close-deep.jo` does not collect.

#### Order of the objects in a hand-off

A hand-off first moved the segments of `r` into the parent like a merge:
the partly filled first segment of `r` topped up the parent's first
segment with its newest objects, and the full ones were spliced in. This
breaks the order of allocation in the parent. A loop that keeps every
object in a variable declared outside it, such as `l = Cons(i, l)`, hands
off a batch every 8 KB. Each hand-off moved the newest objects of the
batch away from the rest.

The order matters because the C library reuses freed memory last in, first
out. A region frees its objects in the order of its segments, so the next
allocations get addresses in the reverse of that order. With the order of
allocation, the addresses of a list built in the next round follow each
other, as on fresh memory. With the order broken, every round scrambles
the addresses more. In 40 rounds of `build(100000)`, which conses 100000
nodes in a loop and returns the list, the share of list links that jump
more than 64 bytes grew from 2% in the first round to 28% in the 40th.
Traversals of the list, the trace of the close of `build` and the marks of
loop collection all got slower. The two scans of the hand-off itself, the
test and the rewrite, cost about 2% of the run.

A hand-off now appends the objects oldest first, which keeps the order of
allocation. The share of far links stays at 2% to 3%, below the 5% of the
commit before iteration regions. The copy of the pointers costs about one
store per object.

Median of 9 interleaved runs (5 for the hot loop), pinned to one core,
against the commit before (2249cdc1). "Spliced" is the hand-off by segments, "Appended" the hand-off
oldest first. Times are user time, and user plus system time where they
differ. Without loop collection the commit before keeps all lists until
`main` returns, 217 MB, and pays for the fresh pages in system time.

| Program | Before | Spliced | Appended |
|---|---|---|---|
| 40 rounds of `build(100000)` | 0.60 s | 0.69 s (+15%) | 0.64 s (+7%) |
| Same, no loop collection | 0.47 s, 0.57 s | 0.52 s (+11%) | 0.50 s (+6%), 0.50 s (-12%) |
| Tight loop | 0.43 s | 0.40 s | 0.39 s |
| Hot loop | 4.10 s | 4.09 s | 4.26 s |
| Permute | 0.50 s | 0.47 s | 0.48 s |

Peak RSS does not change. The hot loop hands nothing off, and its time
moves by up to 8% with the layout of the code alone: with the alignment of
functions and blocks forced, the appending runtime ran it in 3.97 s and the
splicing one in 4.41 s.

### What is removed

- **Loop collection**, superseded by iteration regions. Done in step 4:
  `loopStart` and `collectLoop` in `Regions.jo`, the marking and its header
  bit, the size table, the roots of the frame (`LoopRoots`, `prepareLoops`,
  `checkLoop`, `collectBlocks`), the operand rewrite `LoopOperands` and the
  held count, `JO_LOOP_COLLECT`, `--no-loop-collect`, and the loop
  statistics other than `iteration-regions`. Parts that other code needs
  stay: `mayAllocate(word)`, `directCallee`, now in `Allocations.scala`,
  and `requested`, which ends the batches of iterations. The stress mode
  of iteration regions moved to `JO_LOOP_STRESS`.
- **Placement in loops**, done in step 5: element-store detection
  (`ElementStore`), paths, stores in a loop and the `new` locals of
  [Placing what is stored into the result](#placing-what-is-stored-into-the-result).
  It changes which memory is kept, which the principle forbids. Placement
  of returned values outside loops stays.
- **Passing the result level to indirect calls** is kept. Removing it alone
  costs Richards 27%: `TaskControlBlock$runTask` returns the result of an
  indirect call and would open a region again, 1.33 s instead of 1.05 s.
  It changes only where a returned value is first allocated, never which
  memory is kept, so it needs no rule in the memory guide. See
  [Indirect calls](#indirect-calls).
- **Functions that only allocate their result** is narrowed or dropped, by
  measurement. Its runtime calls allocate in the caller's region, so it can
  move runtime temporaries into the caller: a recursive string builder
  `"(" + walk(l) + "," + walk(r) + ")"` opened no region and took 74.7 MB
  instead of 18.5 MB. Either it keeps only functions whose allocations are
  all their own placed record literals, like constructors, or it goes if
  the fast path recovers Sieve.

What users lose is the collection of long-running state. A `mutable.Map` in
the entry function updated millions of times keeps every replaced value
until the entry function returns. A loop
of prepends `s = c + s` keeps every intermediate string until its function
returns, so it takes memory quadratic in the number of iterations: 30000
prepends of two bytes keep about 880 MB. An append loop `s = s + x` stays
linear, since a string appends in place into a growable buffer. It keeps
one header per step, and its buffers total about twice the last one, at
most about 4 times the final length.

Removing placement in loops brings back the repeated traces of trees built
through `Array.tabulate` or `List.map`: each level of the tree traces and
moves everything below it again. Storage takes about 4 times as long, see
[Placing what is stored into the result](#placing-what-is-stored-into-the-result).

### What is measured

Each step reports time and peak RSS before and after, and the counters of
`JO_MEMSTATS` that explain them: enters, frees, merges, fast paths,
collects, traced objects and skipped enters.

- Iteration regions: Permute, a hot allocating loop, a tight loop with one
  dead object per iteration, an `s = s + x` loop, a prepend loop, Storage
  and Sieve. Times are the median of 5 runs, compared with the commit before
  the step. Single runs vary by up to about 15% with the allocator, so one
  run decides nothing. If the median of Permute or a hot loop is more than
  10% slower, the design is reconsidered before going on. A region per
  iteration was, and batches replaced it (see
  [Batching iterations](#batching-iterations) and
  [Measurements of iteration regions](#measurements-of-iteration-regions)).
- The fast path: Sieve and NBody, against the numbers of
  [Alternative considered](#alternative-considered-merging-a-region-from-which-everything-escapes).
- The no-region rule: `walk`, Sieve and constructors, with the rule
  narrowed and dropped.
- The level use of deep recursion, with `wide` and its `skipped-enters`.

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
  loop, or opens no region, or is past the level limit.
- A value stored into a long-lived object is promoted to that object's region,
  and stays there after the field is overwritten, until that region closes.
  A value replaced in a variable declared outside a loop stays until the
  region of the variable closes. In a function that never returns, such
  values are never freed. Before shared boxes, most of the peak RSS of html5
  under `all` was such values, about 1.5 million objects, in the runtime
  region.
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
  next close of an iteration region, so a loop that calls no region function
  and opens no iteration region reports late, at the next return. A region
  that reached the threshold and closed before then is not reported.

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
`Regions.leave` before each `ret`, passing the result or `null`. A loop with
iteration regions calls `Regions.enterLoop` and `Regions.leaveLoop` at the end
of a batch and `Regions.leaveLoop` at its exits. All four are `noinline`. The
barrier `Regions.barrier`, `Regions.require` and `Regions.requested` are
ordinary functions, which LLVM inlines.

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
