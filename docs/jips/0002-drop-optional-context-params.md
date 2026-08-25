---
author: Fengyun Liu
status: Accepted
created: 2026-08-23
title: Drop optional context parameters
---

# JIP 0002 — Drop optional context parameters

<JipMeta />

> *Il semble que la perfection soit atteinte non quand il n'y a plus rien à
> ajouter, mais quand il n'y a plus rien à retrancher.*
>
> Perfection, it seems, is attained not when there is nothing more to add, but
> when there is nothing more to take away.
>
> — Antoine de Saint-Exupéry, *Terre des Hommes* (1939)

## Summary

This proposal drops **optional context parameters** — the form in which a
declaration carries a default:

```jo
param pageWidth: Int = 80   // dropped
```

Such a default is supplied automatically wherever no binding is in scope. After
this change a declaration states only a requirement, and a caller provides the
value:

```jo
param pageWidth: Int

with pageWidth = 80 in render(document)
```

Nothing else about context parameters changes: `with`, `receives`, `allow`,
shadowing, and capture keep their current meaning.

> `param` states a requirement, `receives` tracks it, `allow` bounds it, and
> `with` provides it.

## Motivation

Context parameters make ambient dependencies explicit and statically checked.
`receives` records what a function needs, `allow` bounds what a block may reach,
and Jo reports a dependency trace when nothing supplies a required parameter.
Optional context parameters make the meaning of a program harder to predict,
and they remove the parameter from the checks.

### It is not obvious where a default is bound

A `with` states its scope. A default does not. It takes effect wherever
propagation stops, and that is a property of the call chain rather than of any
site the reader can point to. Two things follow from having no stated scope.

*Implicit sharing.* The lifetime of a default is decided by the gate structure
of the call chain.

```jo
param counter: Counter = newCounter(0)

def bump(): Int =
  counter.inc()
  counter.get()

def gated(): Int receives none = bump()

def main receives IO.stdout =
  println gated()   // 1
  println gated()   // 1  — a new counter on every gated call
  println bump()    // 1
  println bump()    // 2  — one counter shared by every ungated read
```

Reads that no gate separates from the top level share a single instance of the
default for the whole program. A gate creates a separate instance, and creates
it again on every call. A gate here is any of the three boundaries: an `allow`
that excludes the parameter, a `receives` clause that omits it, or a lambda that
captures without one.

The declaration therefore does not say how many counters the program has, nor
how long any of them lives, and neither do the reads. For a pure default the
difference cannot be observed. For anything stateful, cached, or expensive, it
can.

*Implicit binding may violate access control.* A binding can also appear where
the language forbids writing one. `private` restricts who may bind a context
parameter, but an inserted default is not subject to that check:

```jo
section A
  private param secret: Int = 42

  def getSecret(): Int receives none = secret
end

def main receives IO.stdout =
  println (A.getSecret())                 // 42
  with A.secret = 99 in A.getSecret()     // error: cannot access private member
```

The first call succeeds, because a binding of `A.secret` is created to serve it.
The second shows that `main` may not create that binding itself.

This is a design dilemma, not an oversight. Allowing the binding, which is what
happens today, lets an inserted default do what the programmer at that site is
forbidden to do. Rejecting it would report a private symbol at a call site that
never mentions it, cannot see it, and has no way to satisfy it: `main` would be
told it lacks access to `A.secret`, a name the section deliberately hides.
Neither answer is good, and the question only arises because the binding has no
site of its own.

### Broken propagation stops being reported

Jo reports a context parameter that cannot reach a binding, and names the
function and the read. That diagnostic is the practical payoff of tracking
context statically. A default suppresses it.

Reading a context parameter that `receives` does not list is normally an error:

```jo
param indent: Int

def localOnly(): Int receives none =
  indent    // error: Context parameter not provided: indent
```

Give the declaration a default and the same body compiles, silently pinned to
the declaration's value:

```jo
param indent: Int = 7

def localOnly(): Int receives none =
  indent    // no error, always 7

def viaAllow(): Int receives indent =
  allow none in indent    // no error, also 7
```

The body still reads `indent`, so it looks like it uses ambient context. It does
not. `with indent = 100 in localOnly()` prints `7`, and so does `with indent =
200 in viaAllow()`.

Note what the caller is not told. The binding is not overridden — `receives
none` correctly prevented it from entering, which is what that annotation is
for. The defect is that severing the connection is silent. Without the default
the compiler names the exact function and read, and the programmer either adds
`indent` to `receives` or stops reading it. With the default there is nothing to
notice.

`allow` is affected the same way, which makes the feature a capability hole:
`allow none in e` does not deny `e` all ambient context, only the parameters
that lack a default.

With a lambda it is worse, because nothing marks the cut at all. `receives none`
at least announces that context stops there. A lambda type that omits the
parameter announces nothing:

```jo
param indent: Int = 7

type Fmt = () => Int

def make(): Fmt = () => indent

def main receives IO.stdout =
  val f = make()
  println (with indent = 100 in f())    // 7
```

The lambda captured at its creation site, where nothing was bound, so the `with`
at the call site does nothing. Drop the default and the program is rejected,
with a trace running from `main` through `make` to the read — and the fix,
`type Fmt = () => Int receives indent`, makes it print `100`.

## Specification

### Declaration syntax

In `docs/language/syntax/syntax-summary.md`, change:

```ebnf
param_def = "param" param ["=" block]
```

to:

```ebnf
param_def = "param" param
```

The following is rejected:

```jo
param pageWidth: Int = 80
```

### Binding and propagation

The semantics of `with`, `receives`, `allow`, shadowing, and lambda capture do
not change.

Every context parameter read must be supplied by an active `with` binding or
received from the caller. If no binding reaches an entry point, compilation
fails with the existing missing-context dependency trace. No declaration
produces a hidden default definition, and context resolution never inserts a
value because a binding is absent.

## Consequences

Optional configuration becomes an ordinary parameter. Ordinary default
parameters already cover what an optional context parameter was reached for, in
two shapes that serve different purposes.

*A module-private context parameter with a conventional value.* The context
parameter is internal plumbing — it saves threading a value through private
helpers — and the boundary publishes the default:

```jo
section Engine
  private param pageWidth: Int

  private def layout(content: Markdown): String receives pageWidth = ...

  def render(content: Markdown, pageWidth: Int = 10): String receives none =
    with Engine.pageWidth = pageWidth in layout(content)
```

`private` makes the encapsulation enforceable rather than conventional. The
parameter is not part of the API, and an outside binding is rejected instead of
silently ignored:

```text
|   with Engine.pageWidth = 42 in Engine.render("a")
|        ^^^^^^^^^^^^^^^^
|        Cannot access the private member pageWidth, limit = Engine, site = main
```

*A public context parameter accepted either way.* Here the parameter is part of
the API, and the default parameter defaults to the context parameter itself, so
a caller may choose the syntax:

```jo
section Engine
  param pageWidth: Int

  def render(content: Markdown, pageWidth: Int = Engine.pageWidth): String receives none =
    with Engine.pageWidth = pageWidth in layout(content)
```

`Engine.render(doc, 80)` and `with Engine.pageWidth = 80 in Engine.render(doc)`
both work. The default is evaluated at the call site against the caller's
context, so it cannot discard a binding the caller supplied. When the caller has
no binding either, the requirement propagates outward as an ordinary error —
even though `render` is itself `receives none`:

```text
| def main receives IO.stdout =
|     ^^^^  Context parameter not provided: pageWidth
└──   println (Engine.render("c"))
```

The two shapes should not be crossed. A *public* context parameter with a
*literal* default invites an ambient binding and then ignores it, reproducing
inside the library the behavior this proposal removes from the language. Seal
the parameter with `private`, or default it to itself.

In both shapes the qualified `Engine.pageWidth` is needed inside the section,
because the default parameter shadows the context parameter.

## Alternatives considered

**Keep optional context parameters.** Concise. Rejected because absence is not
evidence of intent, and because a defaulted parameter drops out of `receives`,
`allow`, and capture checking altogether. Documentation cannot fix that.
The one thing the feature is good at, optional configuration at a module
boundary, is better served by an ordinary default parameter, as shown above.

**Supply defaults only at entry methods.** Under this alternative a default
would not repair a missing binding inside a call chain. The compiler would
insert it only where a program entry has none supplied from outside. That is
safer than insertion at arbitrary reads, because broken internal propagation
would stay an error.

It does not justify a language feature. Everything it achieves, an entry method
can already write:

```jo
def main receives IO.stdout =
  with logger = Logging.discard in run()
```

The alternative saves one line per entry, and charges for it with declaration
syntax, a resolution rule, and a definition of "entry method" the language does
not otherwise need.

**Mark the receive site as optional.** A function could declare that it
tolerates the absence of a parameter:

```jo
def render(doc: Doc): Unit receives indent? = ...
```

`indent?` would mean: use the binding if one reaches here, otherwise bind the
declared default. This is more honest than a bare default, because the tolerance
is written at the site that tolerates it rather than at the declaration.

It does not survive the question of where that binding is created. `render` can
be called from contexts that provide `indent` and from contexts that do not, so
the choice has to be made somewhere, and both answers are bad.

Creating it inside `render` requires a dynamic check on every call — is a
binding in scope? That replaces a static question with a runtime one, and it
forces `allow` to become an operation that actually strips bindings at runtime
instead of a compile-time restriction.

Creating it at the call site returns to where this proposal started. The binding
again has no site the reader can point to, and the sharing and access-control
questions of the first problem come back unchanged.
