# Memory Management for the LLVM Backend

This document designs memory reclamation for the 64-bit LLVM backend. It
builds on an earlier design, explicit function regions with deferred
promotion, and changes it in five places:

1. Region functions are chosen by the user in backend configuration, not by a
   language annotation.
2. Cleanup traces only the objects of the closing region.
3. Cleanup allocates nothing. The trace reverses pointers instead of keeping a
   work list.
4. Survivors move to the parent region by handing over storage segments.
5. A region from which something escapes merges into its parent while a
   budget allows, and only collects when the budget is used up.

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
- `req`: the oldest region the object must outlive. Always `req <= phys`.

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
honor it. Region functions are backend configuration instead:

- `--region <function>` makes a function a region function. The flag can be
  repeated. Listing region functions in `jo.toml`, like `--link` mappings, is
  not implemented yet.

The backend does not choose region functions itself. Users who want to reduce
memory use pick them. Choosing them automatically is a separate concern that
can be improved later without changing this design.

### The runtime region

Level 0 is the runtime region. It holds what the runtime sets up before the
program starts, such as singleton objects, program arguments and the standard
streams, and everything `main` allocates outside region functions. The runtime
region never closes: the program exits instead, so freeing it would gain
nothing.

An object that escapes to level 0 lives until the program exits.

### Tail-recursive functions

Jo's tail-call optimization turns a self-recursive function into a loop. A
region function rewritten this way is out of scope for now. Later the compiler
can warn that the region marking is ignored for such a function.

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

String literals, interface tables and other globals are not heap objects. They
carry a header with `phys = 0` and `req = 0`, so the barrier never lowers them
and the trace never enters them.

A string literal is a static `Raw` object whose `content` points into a static
buffer object. Context parameter keys point into static buffers too. Program
arguments are copied into heap buffers at startup, and memory handed to the C
library, such as the alternate signal stack, comes from `calloc`.

## Allocation

`GC.alloc` stays the single allocation entry point. It allocates the object
with `malloc`, writes `phys` and `req` as the current level, and appends the
object to the current region.

A region stores its objects in a linked list of fixed-size segments. Each
segment is an array of object pointers with a count. Allocation fills the
first segment and puts a new one in front when it is full. This is the only
place the region storage grows.

Only the first segment of a region is ever partly filled. The segment size is
therefore not a tuning knob: the index costs one word per object, plus at most
one partly filled segment per open region. A small segment keeps that waste
low for deep recursion through region functions.

Every heap allocation must be an object with a header. Today the runtime also
allocates raw byte buffers, for example in `StringOps.bytes`, `floatToStr` and
`RawBytes`. These become objects with a "no pointers" layout.

A string's `content` field points into its buffer, past the header. The layout
table marks such a slot as interior with a fixed offset, and the trace
subtracts the offset to find the object. Every `Addr` field is such a slot,
so an `Addr` stored in a heap object must point into a buffer or a static
buffer, never into C memory or past the start of the content. The trace checks
the class id of the buffer and stops the program if the rule is broken.
Buffers hold no pointers, so the trace lowers them without descending, and an
interior slot is never reversed.

`malloc` keeps object addresses stable and frees objects one at a time. A
size-class allocator can replace it later without changing the design.

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
older.

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

Cleanup runs no user code, allocates nothing and traces only the objects of
the closing region `r`.

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
    else:
      o.phys = r.parent.num
      keep o                      // pack survivors at the front
  release the empty segments
  give the survivor segments to r.parent
```

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

- Space: none beyond the objects themselves. Cleanup allocates nothing.
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
- A survivor pays again at every enclosing close that collects. It moves up
  one region per collect and is visited by the sweep of each. While escaping
  data still reaches it, it is also traced again: its `req` was lowered only to
  the level of the closing region's parent, which is too young for the next
  close.

So an object costs work at every collecting boundary it passes through alive. A
region pays off when most of what it allocates dies before it closes. It costs
more than it saves when most of what it allocates survives, and the cost grows
with the number of nested regions the survivors pass through.

The worst case is a recursive function that is a region and returns what it
builds, for example

```
def build(n: Int): Option[Node] =
  if n == 0 then None
  else Some(new Node(n, build(n - 1)))
```

Every node survives every enclosing close. The first closes merge, until the
list exceeds the merge budget of about 500 objects. From then on every close
collects and traces the whole list, so building a list of `n` nodes costs
O(n²). On an x86-64 machine, `n = 20000` takes 0.02 seconds without regions
and 40 seconds with `build` as a region function, down from 92 seconds before
merging. Such a function frees
nothing, so it gains nothing from being a region.

### Choosing region functions

Region functions are chosen for their effect on peak RSS:

- Good: a function called repeatedly, for example from a loop, that allocates
  temporaries and returns a small result, such as a number or `Unit`. Its
  garbage dies when it returns, and the memory is reused by the next call.
- Bad: a function that returns most of what it allocates, such as a builder or
  a parser that returns its result. Above all, a recursive one, as shown above.
- Neutral: a function that allocates little. It costs two calls per call and
  frees little.

Measure peak RSS and time with and without a region function. A future
profiling build could report, for each function, how much of the memory
allocated during its calls survives them, which ranks the candidates.

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
500 merged objects.

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
region closes, like depths. A region's levels above its `num` number at most
its merged objects, so with the budget they stay far below the limit of 32767,
and the header keeps its layout. At the limit, `enter` stays in the current
region, as for depths before.

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
address computation. At 32768 levels and 32 bytes per descriptor, the array
takes 1 MB of address space, and only the pages for the levels in use become
resident.

At the close of `r`, let `m` be the minimum of `min` over `[r.num, r.hi]`.
After a merge, the barrier may record into the slot of a merged child, at a
level in `(p.num, p.hi]`. No open region uses those slots, so the scan of the
range finds it. Something escapes `r` exactly when `m < r.num`. Both collect
and merge then pass `m` up:

```
parent.min = min(parent.min, m)
```

An object can escape several regions at once, but the barrier records only at
the innermost one. Passing `m` up covers the others. The invariant is: if an
object `o` has `req` below the `num` of its owner region, the minimum over the
owner's range is at most `o.req`. It holds for every step that lowers a `req`
or moves an object:

- The barrier and `require` record the new `req` at the owner.
- The survivors of a collect move to the parent. Their `req` was set by a
  barrier, `require` or `lower`, so it is at least `m`, which the parent
  receives.
- `lower` lowers an object `e` in an ancestor `Q` to the `req` of its root,
  which is at least `m`. Every close between `r` and `Q` passes `m` up, so `Q`
  sees it and runs pass 1.
- A merge keeps the child's records in the parent's range.

## Interaction with the optimizer

The design holds under LLVM's optimizations:

- Inlining a region function keeps `enter` and `close`, which are explicit
  calls. Losing a boundary would only merge regions, which is safe.
- The barrier is inlined code with a store. Removing a dead heap store leaves
  the barrier's promotion in place, which is conservative.
- No load can move across `close`, because LLVM sees an opaque call that may
  write memory.
- Allocations are never removed, because every object escapes into its region's
  segment.

`enter`, `close` and `lower` are marked `noinline` to keep the boundary opaque.
The barrier may be inlined.

## Limitations

- Programs without configured region functions free nothing before `main`
  returns. This is the current behavior.
- Objects promoted to the runtime region stay until the program exits.
- Garbage accumulates inside a long-running region, for example in a loop.
  Moving the loop body into a region function reclaims it per iteration.
- Survivors are traced again at every enclosing close that collects. Once a
  region holds more than the merge budget, every enclosing close collects, so a
  region function whose results are large and pass through many regions is
  slow (see Cost).
- A merge holds the garbage of the merged region until a later close, up to
  the budget of 8 segments per region.
- A removed escape stays promoted until its destination closes, as in the earlier design.
- `malloc` per object is slower than the current bump allocator.

## Later: a faster allocator

Allocating each object with `malloc` and freeing it with `free` is simple and
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
  startup. A descriptor is 32 bytes, so the array takes 1 MB of address space,
  and only the pages of the levels in use become resident. `enter` takes the
  descriptor above the current region's levels.
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
hold pointers, never the objects themselves.

```
@runtime.state (static, zeroed)
┌───────────┬──────────┬───────┬─────────┬───────────┬─────────────┬──────────┬──────────┐
│ regions ● │ region ● │ level │ skipped │ segPool ● │ segPoolSize │ stackLow │ stackTop │
└─────┼─────┴────┼─────┴───────┴─────────┴─────┼─────┴─────────────┴──────────┴──────────┘
      │          │                             └──► free segment ──► …   (at most 64)
      ▼          ▼
regions: one descriptor per level (32 bytes each)
┌───────────┬───┬───┬───┬─────┬─────┬─────┬──────────────────┐
│ 0 runtime │ 1 │ … │ p │ p+1 │ p+2 │ num │ unused … 32767   │
└───────────┴───┴───┴───┴─────┴─────┴──┼──┴──────────────────┘
                     └─ levels of p ─┘ │   p.hi = p+2: the regions merged into p
                                       ▼
current region descriptor
┌────────────┬─────────┬─────────┬──────────┬────────────┬───────────┐
│ parent i32 │ hi i32  │ min i32 │ segs i32 │ firstSeg ● │ lastSeg ● │
└────────────┴─────────┴─────────┴──────────┴─────┼──────┴─────┼─────┘
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
┌─────────┬───────────────────────────┬────────┬────────┬─────
│ cid i32 │ gc i32                    │ slot 1 │ slot 2 │ …
│         │ normal [0|phys:15|req:16] │        │        │
│         │ on path [1|cursor:31]     │        │        │
└─────────┴───────────────────────────┴────────┴────────┴─────
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
`Regions.leave` before each `ret`, passing the result or `null`. Both are
`noinline`. The barrier `Regions.barrier` is an ordinary function, which LLVM
inlines.

Usage:

```
jo compile --llvm --region app.process --region app.step app.jo -o app
jo compile --llvm --region-policy all app.jo -o app
```

`--region-policy` opens regions in more functions besides those named by
`--region`: `none` (the default), `scalar` for functions returning a primitive
value other than `Unit`, or `all`.

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
