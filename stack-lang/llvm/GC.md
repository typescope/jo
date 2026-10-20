# Memory Management for the LLVM Backend

This document designs memory reclamation for the 64-bit LLVM backend. It
builds on an earlier design, explicit function regions with deferred
promotion, and changes it in four places:

1. Region boundaries are backend configuration, not a language annotation.
2. Cleanup traces only the objects of the closing region.
3. Cleanup allocates nothing. The trace reverses pointers instead of keeping a
   work list.
4. Survivors move to the parent region by splicing storage segments.

The design is single-threaded, like the rest of the native runtime.

## Overview

The heap is a stack of regions. The root region exists for the whole program.
A call to a region function opens a child region and closes it on return. All
allocations go into the current region.

Every object records two region depths:

- `phys`: the region whose storage holds the object.
- `req`: the shallowest region the object must outlive. Always `req <= phys`.

A heap store `b.f = c` lowers `c.req` to `b.req` if needed. When a region
closes, objects whose `req` is shallower than the region are escape roots.
Their requirement spreads to the region's objects they reach. Then the region
frees every object still required only by itself and hands the survivors to
its parent.

Safety does not depend on scanning the stack. A frame only ever holds
pointers into its own region or an ancestor region, and a region outlives
every frame that can see it.

## Region boundaries

`@region` cannot be part of the language because only the native backend can
honor it. The LLVM backend decides region boundaries itself:

- `--region <function>` makes a function a region function. The flag can be
  repeated, and a project can list region functions in `jo.toml`, like
  `--link` mappings.
- A default rule picks region functions when none are configured, so ordinary
  programs reclaim memory without configuration. The rule is an open question
  (see below).

Merging regions is always safe. It only delays reclamation. The backend
therefore may ignore a region boundary when it is inconvenient, for example
beyond the depth limit.

## Object header

Slot 0 of every heap object is `cid: i32 | gc: i32`. The `gc` half has two
forms:

```
normal    [0 | phys: 15 | req: 16]
on path   [1 | cursor: 31]
```

The on-path form is used only during cleanup (see below).

Depth 0 is the root region. Region depth is limited to 32767 by the `phys`
field. A region function called at the limit runs in the current region
instead of opening a new one.

Since active regions form a chain, the lowest common ancestor of two active
regions is the shallower one. The barrier therefore compares depths. A region
descriptor is never needed per object.

## Static data

String literals, interface tables and other globals are not heap objects. They
carry a header with `phys = 0` and `req = 0`, so the barrier never lowers them
and the trace never enters them.

String literal globals must therefore get the same header layout as heap
objects.

## Allocation

`GC.alloc` stays the single allocation entry point. It allocates the object
with `malloc`, writes `phys` and `req` as the current depth, and appends the
object to the current region.

A region stores its objects in a linked list of fixed-size segments. Each
segment is an array of object pointers. Allocation adds a segment when the
last one is full. This is the only place the region storage grows.

Every heap allocation must be an object with a header. Today the runtime also
allocates raw byte buffers, for example in `StringOps.bytes`, `floatToStr` and
`RawBytes`. These become objects with a "no pointers" layout.

A string's `content` field points into its buffer, past the header. The layout
table marks such a slot as interior with a fixed offset, and the trace
subtracts the offset to find the object.

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
  slot = c
```

Initialization stores need no barrier. A new object `b` has `req` equal to the
current depth, and any `c` it can refer to already has `c.req` at that depth or
shallower.

Codegen already routes every field store through one helper, `storeField`,
which tells initialization from mutation. The barrier goes there.

Stores performed by the runtime also need the barrier:

- `RefArray.set` writes array elements with `writeAddr`. It gets a
  barrier-aware primitive `writeRef`, which the 32-bit backends rewrite to a
  plain store.
- Context parameters provided with `with ... in` are stored in runtime
  structures. Those stores go through the barrier too.

The barrier changes only the stored object. Propagation to its children is
deferred to cleanup.

## Region entry and exit

A region function compiles to:

```
enter()                     // push a region at depth + 1
result = body
if result is a pointer:
  require(result, depth - 1)    // result.req = min(result.req, depth - 1)
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

The gist's algorithm follows pointers into ancestor regions. That is not
needed. When the trace reaches an object `e` in an ancestor region `Q`, it
lowers `e.req` and stops. Then `e.req < depth(Q)`, so `e` is an escape root of
`Q`, and its children are traced when `Q` closes. Until then they stay in a
live region and are safe.

An object `d` of `r` that `e` points to is covered too. The store into `e` ran
the barrier, so `d` survives `r` and moves up with the survivors. When `Q`
closes, the trace from `e` reaches `d` and lowers it further.

### Algorithm

```
close(r):
  for o in r.objects:
    if o.req < depth(r):          // escape root
      lower(o, o.req)

  for o in r.objects:             // sweep
    if o.req == depth(r):
      free(o)                     // never dereferences other objects
    else:
      o.phys = depth(r) - 1
      keep o                      // pack survivors at the front
  release the empty segments
  splice the remaining segments onto r.parent
  current region = r.parent
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
      if c != null and not c.onPath and c.phys == depth(r) and c.req > d:
        field(cur, i) = prev      // reverse the edge
        prev = cur
        cur = c
        cur.gc = onPath(cursor = 0)
      else:
        if c != null and not c.onPath and c.req > d:
          c.req = d               // in an ancestor: lower only
        cur.cursor = i + 1
    else:
      cur.gc = normal(phys = depth(r), req = d)
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
cursor covers any array. The tag bit is checked first, because the depths of
an object on the path are not stored.

The traversal descends only into an object with `req > d`. That condition also
replaces the gist's `processed` map: an object already lowered to `d` or
shallower has nothing left to propagate. Cycles end at objects on the path.

### Cost

- Space: none beyond the objects themselves.
- Time: an object of `r` is traversed once per lowering of its `req` within one
  close. `req` only decreases, so this is bounded by the region depth, and it is
  once in the common case. Objects outside `r` are never traversed.
- A survivor moves up one region per close. At each later close it is an escape
  root again, but its children are already lowered, so its trace costs one
  pass over its fields.

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

Jo's tail-call optimization turns a self-recursive region function into a loop
inside one region. That is safe, but garbage accumulates across iterations. The
backend can instead open and close a region per iteration.

## Limitations

- Programs without region functions never free memory. This is the current
  behavior.
- Objects promoted to the root region stay until the program exits.
- Garbage accumulates inside a long-running region, for example in a loop.
- A removed escape stays promoted until its destination closes, as in the gist.
- `malloc` per object is slower than the current bump allocator.

## Open questions

- The default rule for region functions. Candidates: every function called
  directly from `main`, or functions whose parameters and result are small
  values, such as numbers, strings or `Unit`.
- Whether loops produced by tail-call optimization should open a region per
  iteration.
- Whether to replace `malloc` with size classes in region-owned chunks.
  Freeing then requires retention accounting for chunks with survivors.

## Plan

Each step ends with verification and a commit, as for the backend.

1. Headers and layouts. Header fields, static headers for literals, headers on
   raw buffers, the layout table, and `writeRef`. No region functions yet, so
   behavior is unchanged.
2. Regions. Segments, `enter`, `close`, `lower`, the barrier and `--region`.
   Tests: escapes through fields, arrays, closures, context parameters and
   results, cycles inside a region, cycles across regions, and repeated region
   calls.
3. The default region rule, measured on `tests/performance`.
