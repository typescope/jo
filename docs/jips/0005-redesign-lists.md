---
author: Fengyun Liu
status: Draft
created: 2026-09-27
title: Redesign lists
---

# JIP 0005 — Redesign lists

<JipMeta />

## Summary

This proposal replaces the 32-way trie behind `List` with a flat array. A list
is a window onto an array: the array, the index of its first element and its
size.

```jo
class List[T]
  private val arr: Array[T]
  private val start: Int
  private val _size: Int
  private val claim: ListImpl.Claim
```

Appending writes into free space at the end of the array, which lists share.
A small runtime object, the *claim*, makes sure each free slot is taken by one
list only. `slice`, `take` and `drop` share the array instead of copying.
`List.updated` is removed.

## Motivation

`List` is the sequence type of the language. List literals, varargs and
sequence patterns all produce or consume one, so its design shapes both what
programs cost and how people write them. The trie serves none of the three
concerns below well.

### Simplicity

A trie is a tree of nodes, each a class instance wrapping an array:

```
List ─► Internal ─► Array ─► Internal ─► Array ─► … ─► Leaf ─► Array ─► element
```

Every operation walks or rebuilds this tree. Appending copies a path of nodes,
and building a list bottom up needs its own code, repeated in `ListBuilder`
and in a leaf-aware merge sort in `Sorting`. The implementation is about 1,160
lines across `List.jo`, `ListBuilder.jo` and `Sorting.jo`. The array version
is about 780. The internal `ListImpl` section shrinks from 228 lines to 77,
and the sort becomes a call to the existing `Sorting.sortArray`.

The mental model gets simpler too. A list is a slice of an array, like a slice
in Go, except that it never changes. Programmers can predict what an operation
costs without knowing how a trie is balanced.

### Performance

**Small lists are common.** Most lists in practice are short: arguments,
fields, results of a `select`, the elements of a literal. The trie gives them
no advantage and adds an indirection. Reading an element of a list of at most
32 elements takes three loads (the list, the `Leaf`, the array) and a tag test
on the node. The array takes two loads and no test. Larger lists add two loads
and a test per level of the trie, and none with the array.

**Users do use `+` and `++`.** Building a list with `acc = acc + v` is the
obvious way to write it, and the library does it too, for example in
`List.groupBy`. On the trie, every `+` copies the last leaf, of up to 32
elements, and one array per level, and allocates a node per level. On the
array, `+` writes one slot and allocates one `List`. Growing the array is
amortized by doubling. `++` copies only its argument.

**`slice` is common in sequence patterns.** A rest pattern binds a sublist,
which the pattern matcher computes with `slice`:

```jo
while list is [head, ..tail] do
  sum = sum + head
  list = tail
```

On the trie, `slice` copies the sublist, so this loop is O(n²). On the array,
`slice` shares the elements and the loop is O(n). A slice smaller than a
quarter of its array is copied instead, so that a short sublist does not keep
a long array alive. The copies shrink geometrically, so they add up to O(n).

Measured on the JavaScript backend (Node 22), in whole-process time, best of
three runs, with the same compiler and each version of the standard library:

| Benchmark | Trie | Array | Speedup |
|---|---:|---:|---:|
| `l = l + i`, 1,000,000 times | 1311 ms | 311 ms | 4.2× |
| `l = l ++ [i, i + 1, i + 2]`, 200,000 times | 1015 ms | 313 ms | 3.2× |
| `while l is [h, ..t]` over 20,000 elements | 3917 ms | 210 ms | 18.7× |
| `[i, i + 1, i + 2]` then `l[1]`, 1,000,000 times | 512 ms | 413 ms | 1.2× |
| 10,000,000 scattered `l[i]` on 1,000,000 elements | 2813 ms | 511 ms | 5.5× |

### Usability

With the trie, the natural way to build a list is also the slow way. The
documentation of `ListBuilder` warns that `acc = acc + v` in a loop "is much
more expensive than it looks" and sends users to a builder, or to
`mutable.List`. Both are mutable objects that a functional program must thread
through a loop and then convert. With the array, `+` is O(1) amortized, and
the code people write first is the code that performs well.

## Design

### Representation

A list holds an array, a start index, a size and a claim. Its elements are
`arr[start]` to `arr[start + size - 1]`. Several lists may share one array,
and the array may be larger than any of them. A list never reads past its own
end, so what lies beyond it does not concern it.

`get` checks the index against the size and reads `arr[start + i]`. Iteration
walks the same range.

### Appending in place

`l + v` asks whether it may take the slot just past its end. If it may, it
writes `v` there and returns a list over the same array with one more element.
Otherwise it copies its elements into a new array of twice the size and
appends there. `++` does the same with the slots its argument needs.

The older list is unaffected either way: its size did not change, so it never
sees the new slot. This is what keeps lists immutable while they share arrays.

### Claims

Two lists may end at the same slot. In

```jo
val a = l + 1
val b = l + 2
```

`a` and `b` both want the slot past the end of `l`. If both wrote it, `a`
would read back `2`. The claim decides: it records how far the array has been
taken, and a list may extend in place only if it ends exactly there. `a` takes
the slot and moves the claim past it. `b` then finds that `l` no longer ends at
the claim and copies.

A list made by `take` or `slice` ends before the claim, so it always copies
when it appends. A list made by `drop` ends where the original ends, so the
two compete for the next slot like any other pair of lists.

The claim is an abstract type with two deferred functions:

```jo
private[jo] section ListImpl
  type Claim

  defer def newClaim(used: Int): Claim receives none
  defer def tryExtend(claim: Claim, stop: Int, count: Int): Bool receives none
```

`tryExtend` takes the `count` slots from `stop` on if `stop` is where the
taken slots end. After it returns `true`, the caller is the only one that
ever writes those slots.

### Threads

Jo has no concurrency primitives, but some backends let threads started
through the FFI reach Jo values. There, two threads could run `tryExtend` on
the same claim at once, both see the slot free and both write it. Each runtime
therefore defines the claim for its own threading model:

| Runtime | Claim |
|---|---|
| JavaScript | compare and bump. Workers share no objects. |
| Native, interpreter | compare and bump. They run a single thread. |
| Python, Ruby | compare and bump, only on the thread that created the claim |

On Python and Ruby, the claim records the thread that created it, from
`threading.get_ident()` and `Thread.current.object_id`. Only that thread
extends the array in place. Every other thread copies and then owns the copy,
so it too appends in O(1) amortized from then on. As one thread writes each
array's free slots, no synchronization is needed. Reading needs none either:
a list only reads slots that were written before the list existed.

If Jo gains concurrency primitives, these runtimes can switch to a
compare-and-swap on the claim without any change to `List`.

### Builders

`ListBuilder` keeps its API. It fills an array directly, and `result` hands
that array to the list it returns, with a fresh claim. If less than half of
the array is used, `result` copies the elements into an array of the exact
size instead, so that a short list does not hold a long array.

Because the list may then append into the free slots itself, `result`
consumes the builder. Adding after `result`, or calling `result` again, lets
two lists write the same slots. The builder does not check this. A builder is
a local, mutable helper, and keeping to one result is up to its user. List
literals and varargs, which the typer lowers to builder chains, call `result`
once.

### Removing `updated`

On an array, `updated` has to copy the whole list, where the trie copied one
path. Code that changes elements by index is better served by `mutable.List`,
so `updated` is removed rather than kept at O(n).

## Costs

- **Branching appends copy.** Of `l + a` and `l + b`, the second copies all of
  `l`. Code that extends one list many times, such as `base + x` in a loop over
  `x`, is O(n) per append where the trie was O(log n). Such code is rare. Most
  appends extend the newest list.
- **Spare capacity.** An array grown by appending may be up to twice as large
  as its longest list. Arrays built by `map`, `fill`, `tabulate`, `sort` and
  exactly sized builders have no spare capacity.
- **A claim per array.** Each new array comes with a claim object, so a small
  list costs three objects, as it did with the trie. This is why the
  small-list benchmark gains least.
- **Sharing keeps arrays alive.** A slice keeps its array alive. The
  quarter rule bounds the waste to four times the slice.

## Prior art

The design is not new in its parts. It combines ideas from several languages.

- **Appending at the frontier comes from D.** A D slice may append in place
  only if it ends where the used part of its memory block ends. The runtime
  records that used length with the block, and a slice that ends earlier
  reallocates instead of overwriting what another slice appended. The claim
  plays the role of that used length. D slices are mutable, and the rule
  there prevents one slice from stomping on another. Here it is what keeps
  shared lists immutable. See Steven Schveighoffer, [D Slices][d-slices].
- **Erlang uses the same trick for an immutable value.** Only the binary
  returned by the latest append can be extended cheaply, in place. Appending
  to an older one copies it, so that earlier variables keep their values. See
  the Erlang efficiency guide,
  [Constructing and Matching Binaries][erlang-binaries].
- **The mental model is Go's slice.** A Go slice is a window onto an array,
  given by a pointer, a length and a capacity, and `append` writes into the
  spare capacity. See [Go Slices: usage and internals][go-slices]. Unlike in Go,
  a list never sees another list's appends, because the claim hands each spare
  slot to one list only.
- **The trie this proposal replaces** follows Phil Bagwell's hash array mapped
  tries and Rich Hickey's `PersistentVector` in Clojure. The fixed-depth trie
  with tails, considered below, follows Scala's `Vector` as redesigned by
  Stefan Zeiger for Scala 2.13.

[d-slices]: https://dlang.org/articles/d-array-article.html
[erlang-binaries]: https://www.erlang.org/doc/system/binaryhandling.html
[go-slices]: https://go.dev/blog/slices-intro

## Alternatives considered

**A linked list of `Nil` and `Cons` cells.** This is the list of Lisp, ML,
Haskell and Scala, and it is the simplest immutable sequence there is.
Prepending and splitting off the head are O(1), and the tail is shared rather
than copied, so `[head, ..tail]` costs nothing. But Jo's list is indexed:
sequence patterns are defined in terms of `size`, `get` and `slice`, and
patterns such as `[.., last]` or `[x, y, ..rest]` read by index. On a linked
list, `get`, `size` and appending at the end are all O(n), so each of those
patterns becomes a walk, and `acc = acc + v` in a loop becomes quadratic. The
idiomatic workaround is to build in reverse with `::` and reverse at the end,
which is the kind of ceremony this proposal removes. A linked list also
allocates one cell per element and scatters the elements across memory, where
an array keeps them together.

**In-place updates through reference counting.** Roc, Lean 4 and Koka update a
value in place when its reference count shows that nothing else refers to it.
See Ullrich and de Moura, [Counting Immutable Beans][beans], and Reinking et
al., [Perceus][perceus]. This needs exact reference counts, and no Jo backend
keeps them. A runtime that did could still use them for appending, inside its
own claim. The claim goes further for appending: a list is extended in place
even when it is shared, as long as no other list has taken the next slot.

[beans]: https://arxiv.org/abs/1908.05647
[perceus]: https://www.microsoft.com/en-us/research/uploads/prod/2020/11/perceus-tr-v1.pdf

**Keep the trie without wrappers.** The wrappers exist because the type of a
node depends on its depth. An unsafe cast would remove them, but a cast in the
standard library undermines the type safety of the language. A union with one
case per depth (`T0(Array[T])`, `T1(Array[Array[T]])`, …) is type-safe and
leaves one tag test per list, not per node. It still needs a six-way match in
every operation, and it keeps all the costs of the trie other than the
indirection.

**A trie of fixed depth.** If every list has the same depth, the root has a
known type and needs no tag. With tails for the last leaf and the last
interior node, as in Scala's `Vector`, reads take two to four loads and appends
are cheap. This is a good design, but it keeps a tree, is more complex than an
array and does not make `slice` O(1).

**A flat array copied on every append.** This is the simplest design, but it
makes `acc = acc + v` in a loop quadratic, which is the usability problem this
proposal sets out to fix.

**Claims with compare-and-swap.** A claim updated atomically would let any
thread append in place. This needs concurrency primitives in the standard
library, which deserve their own design. The thread-owned claim is safe
without them, and the switch can be made inside the runtimes later.

## Compatibility

- Source code that calls `List.updated` must change.
- Source code that uses a `ListBuilder` after calling `result` must change.
- Every runtime must link `jo.ListImpl.newClaim` and `jo.ListImpl.tryExtend`.
  The bundled runtimes provide them. A custom runtime supplied with `--link`
  must provide them too.
- Libraries must be recompiled against the new standard library.

## Future work

- **Remove `ListBuilder`.** With `+` O(1) amortized, the builder mostly serves
  the typer's lowering of list literals and varargs. That lowering could fill
  an array and wrap it directly, after which the builder could be removed.
- **Avoid allocating claims.** A list whose array is full can never extend in
  place, so it could share one claim with every other such list. Small lists
  built by literals would then cost two objects.
- **Compare-and-swap claims** on the Python, Ruby and JVM backends, once Jo has
  concurrency primitives.
