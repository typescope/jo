---
author: Fengyun Liu
status: Draft
created: 2026-09-27
title: Design immutable lists
---

# JIP 0005 — Design immutable lists

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
`List.updated` stays, but copies the list.

## Motivation

`List` is the most important data structure in Jo. List literals, varargs and
sequence patterns all produce or consume one, so its design shapes both what
programs cost and how people write them. The trie implementation has several
problems.

### Simplicity

A trie is a tree of nodes, and every operation walks or rebuilds it. The
implementation takes about 1,160 lines across `List.jo`, `ListBuilder.jo` and
`Sorting.jo`.

Programmers should also have a simple mental model of the list, so that they
can tell what an operation costs. With a trie, that takes knowing how the tree
is laid out: how deep it is, where the leaves end and which path an update
copies.

### Performance

**Small lists are common.** Most lists in practice are short: arguments,
fields, results of a `select`, the elements of a literal. The trie gives them
no advantage and adds an indirection.

**Users do use `+` and `++`.** Building a list with `acc = acc + v` is the
obvious way to write it. On the trie, every `+` involves new allocations and
copying.

**`slice` is common in sequence patterns.** A rest pattern binds a sublist,
which the pattern matcher computes with `slice`:

```jo
while list is [head, ..tail] do
  sum = sum + head
  list = tail
```

On the trie, `slice` copies the sublist, so this loop is O(n²).

### Usability

To address performance with list construction, programmers are asked to use
`ListBuilder`, which is more cognitive overhead than `acc = acc + v`.

## Design

### Representation

A list holds an array, a start index, a size and a claim. Its elements are
`arr[start]` to `arr[start + size - 1]`. Several lists may share one array,
and the array may be larger than any of them. A list never reads past its own
end, so what lies beyond it does not concern it.

<svg viewBox="0 0 720 308" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Four lists sharing one array" style="display:block;margin:1.5rem auto;max-width:100%;height:auto;font-family:system-ui,sans-serif">
  <text x="78" y="77" text-anchor="end" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">arr</text>
  <text x="115.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">0</text>
  <rect x="90" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="115.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">a</text>
  <text x="165.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">1</text>
  <rect x="140" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="165.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">b</text>
  <text x="215.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">2</text>
  <rect x="190" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="215.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">c</text>
  <text x="265.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">3</text>
  <rect x="240" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="265.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">d</text>
  <text x="315.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">4</text>
  <rect x="290" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="315.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">e</text>
  <text x="365.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">5</text>
  <rect x="340" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="365.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">f</text>
  <text x="415.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">6</text>
  <rect x="390" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="415.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">g</text>
  <text x="465.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">7</text>
  <rect x="440" y="50" width="50" height="44" style="fill:var(--vp-c-brand-soft,rgba(4,120,87,.12));stroke:var(--vp-c-brand-1,#047857);stroke-width:1.5"/>
  <text x="465.0" y="77" text-anchor="middle" font-size="14" font-family="ui-monospace,monospace" style="fill:var(--vp-c-text-1,#213547)">h</text>
  <text x="515.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">8</text>
  <rect x="490" y="50" width="50" height="44" stroke-dasharray="4,3" style="fill:none;stroke:var(--vp-c-text-3,#9ca3af);stroke-width:1.5"/>
  <text x="565.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">9</text>
  <rect x="540" y="50" width="50" height="44" stroke-dasharray="4,3" style="fill:none;stroke:var(--vp-c-text-3,#9ca3af);stroke-width:1.5"/>
  <text x="615.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">10</text>
  <rect x="590" y="50" width="50" height="44" stroke-dasharray="4,3" style="fill:none;stroke:var(--vp-c-text-3,#9ca3af);stroke-width:1.5"/>
  <text x="665.0" y="40" text-anchor="middle" font-size="11" style="fill:var(--vp-c-text-2,#476582)">11</text>
  <rect x="640" y="50" width="50" height="44" stroke-dasharray="4,3" style="fill:none;stroke:var(--vp-c-text-3,#9ca3af);stroke-width:1.5"/>
  <text x="590" y="112" text-anchor="middle" font-size="12" style="fill:var(--vp-c-text-2,#476582)">free slots</text>
  <line x1="490" y1="2" x2="490" y2="100" style="stroke:var(--vp-c-brand-1,#047857);stroke-width:3"/>
  <text x="498" y="14" font-size="12" font-family="ui-monospace,monospace" style="fill:var(--vp-c-brand-1,#047857)">claim: used = 8</text>
  <path d="M93,124 L93,132 L487,132 L487,124" style="fill:none;stroke:var(--vp-c-text-2,#476582);stroke-width:1.5"/>
  <text x="93" y="150" font-size="12" style="fill:var(--vp-c-text-1,#213547)"><tspan font-family="ui-monospace,monospace" font-weight="600">l</tspan>  start 0, size 8</text>
  <path d="M93,170 L93,178 L237,178 L237,170" style="fill:none;stroke:var(--vp-c-text-2,#476582);stroke-width:1.5"/>
  <text x="93" y="196" font-size="12" style="fill:var(--vp-c-text-1,#213547)"><tspan font-family="ui-monospace,monospace" font-weight="600">l.take(3)</tspan>  start 0, size 3</text>
  <path d="M193,216 L193,224 L387,224 L387,216" style="fill:none;stroke:var(--vp-c-text-2,#476582);stroke-width:1.5"/>
  <text x="193" y="242" font-size="12" style="fill:var(--vp-c-text-1,#213547)"><tspan font-family="ui-monospace,monospace" font-weight="600">l.slice(2, 4)</tspan>  start 2, size 4</text>
  <path d="M343,262 L343,270 L487,270 L487,262" style="fill:none;stroke:var(--vp-c-text-2,#476582);stroke-width:1.5"/>
  <text x="343" y="288" font-size="12" style="fill:var(--vp-c-text-1,#213547)"><tspan font-family="ui-monospace,monospace" font-weight="600">l.drop(5)</tspan>  start 5, size 3</text>
</svg>

In the picture, four lists share one array, and none of them copied an
element. `l` and `l.drop(5)` both end at the claim, so either may take slot 8
when it appends. `l.take(3)` and `l.slice(2, 4)` end before the claim, so they
copy when they append.

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

### Thread Safety

Two threads could run `tryExtend` on the same claim at once, both see the slot
free and both write it. Each runtime therefore defines the claim for its own
threading model:

| Runtime | Claim |
|---|---|
| JavaScript | compare and bump. Workers share no objects. |
| Python, Ruby | compare and bump, only on the thread that created the claim |

In the current implementation, the claim on Python and Ruby records the thread
that created it, from `threading.get_ident()` and `Thread.current.object_id`.
Only that thread extends the array in place. Every other thread copies and then
owns the copy, so it too appends in O(1) amortized from then on.

This is one choice among several. A claim guarded by a lock would let any
thread extend the array in place, at the cost of taking the lock on every
append.

The design is flexible enough to support any runtime platform. A runtime only
has to provide `newClaim` and `tryExtend` for its own threading model, and
`List` does not change. If Jo gains concurrency primitives, for example, a
runtime can switch to a compare-and-swap on the claim.

## Evaluation

Measured on the JavaScript backend (Node 22), in whole-process time, best of
three runs, with the same compiler and each version of the standard library:

| Benchmark | Trie | Array | Speedup |
|---|---:|---:|---:|
| `l = l + i`, 1,000,000 times | 1311 ms | 311 ms | 4.2× |
| `l = l ++ [i, i + 1, i + 2]`, 200,000 times | 1015 ms | 313 ms | 3.2× |
| `while l is [h, ..t]` over 20,000 elements | 3917 ms | 210 ms | 18.7× |
| `[i, i + 1, i + 2]` then `l[1]`, 1,000,000 times | 512 ms | 413 ms | 1.2× |
| 10,000,000 scattered `l[i]` on 1,000,000 elements | 2813 ms | 511 ms | 5.5× |

The implementation also shrinks, from about 1,160 lines to about 780.

The design has costs too:

- **Branching appends copy.** Of `l + a` and `l + b`, the second copies all of
  `l`. Code that extends one list many times, such as `base + x` in a loop over
  `x`, is O(n) per append where the trie was O(log n). Such code is rare. Most
  appends extend the newest list.
- **`updated` copies.** Replacing one element is O(n) where the trie was
  O(log n).
- **Spare capacity.** An array grown by appending may be up to twice as large
  as its longest list. Arrays built by `map`, `fill`, `tabulate`, `sort` and
  exactly sized builders have no spare capacity.
- **Sharing keeps arrays alive.** A slice keeps its array alive. A slice
  smaller than a quarter of its array is copied instead, which bounds the
  waste to four times the slice.

## Related work

The design takes inspiration from several languages.

- **Appending at the frontier comes from D.** A D slice may append in place
  only if it ends where the used part of its memory block ends. The runtime
  records that used length with the block, and a slice that ends earlier
  reallocates instead of overwriting what another slice appended. The claim
  plays the role of that used length. See Steven Schveighoffer,
  [D Slices][d-slices].
- **The mental model is Go's slice.** A Go slice is a window onto an array,
  given by a pointer, a length and a capacity, and `append` writes into the
  spare capacity. See [Go Slices: usage and internals][go-slices]. Unlike in Go,
  a list never sees another list's appends, because the claim hands each spare
  slot to one list only.
- **The trie this proposal replaces** follows Phil Bagwell's hash array mapped
  tries and Rich Hickey's `PersistentVector` in Clojure. The fixed-depth trie
  with tails, considered below, follows Scala's `Vector` as redesigned by
  Stefan Zeiger for Scala 2.13.
- **Linked lists of `Nil` and `Cons` cells** are the list of Lisp, ML,
  Haskell and Scala. They make `[head, ..tail]` O(1), but indexing and
  appending O(n).
- **In-place updates through reference counting.** Roc, Lean 4 and Koka update
  a value in place when its reference count shows that nothing else refers to
  it. See Ullrich and de Moura, [Counting Immutable Beans][beans], and Reinking
  et al., [Perceus][perceus]. Here, a list is extended in place even when it is
  shared, as long as no other list has taken the next slot.

[d-slices]: https://dlang.org/articles/d-array-article.html
[go-slices]: https://go.dev/blog/slices-intro
[beans]: https://arxiv.org/abs/1908.05647
[perceus]: https://www.microsoft.com/en-us/research/uploads/prod/2020/11/perceus-tr-v1.pdf

## Alternatives considered

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

- `List.updated` keeps its signature but becomes O(n).
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
