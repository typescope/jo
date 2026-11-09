---
author: Fengyun Liu
status: Draft
created: 2026-11-09
title: Capability injection
---

# JIP 0012 — Capability injection

<JipMeta />

## Summary

Let the build wire capabilities into one deferred function. The compiler
creates each capability with a factory function or binds it to a literal value,
then calls the implementation of the deferred function with those capabilities
in scope:

```bash
jo compile --python app.jo \
  --inject  jo.main \
  --provide fs.root=fs.Root \
  --provide fs.readonly=fs.ReadOnly \
  --provide gh.client=gh.GitHub \
  --bind    gh.repo=typescope/jo \
  --allow   fs.readonly,gh.client,IO.stdout
```

The proposal is purely additive. Programs and builds that do not use the new
options are unaffected.

## Motivation

The entry point `jo.main` is a deferred function with a fixed signature:

```jo
defer def main: Unit receives IO.stdout, IO.stderr, IO.args = pass
```

A library that offers a capability to applications must therefore own the
entry point. It links `jo.main` to its own `platformMain`, creates the
capability there, and calls another deferred function that the application
implements. The process-monitor demo follows this pattern:

```jo
def platformMain: Unit receives stdout =
  val processImpl = new ProcessImpl()
  val systemImpl  = new SystemImpl()
  // ...
  with process = processImpl, system = systemImpl, ... in
    SystemAPI.startMonitor(intervalSecs)
```

```bash
jo compile --python \
  --link jo.main=SystemRuntime.platformMain \
  --link SystemAPI.startMonitor=ProcessMonitor.startMonitor \
  ...
```

This does not compose. Only one library can own `jo.main`. An application that
needs a network capability from one library and a PDF capability from another
must write its own platform layer: a new `main` that creates both capabilities,
binds them, and calls a new deferred entry point with a hand-written
signature. The glue grows with every capability, and every application that
combines the same libraries writes it again.

The glue also does one thing that a flat list of factories cannot express: it
narrows authority. A platform may open a file system, wrap it in a read-only
view, and hand only the view to the application. The broad capability exists
in the program but never reaches the untrusted code.

The glue carries no information beyond *which factory creates which
capability* and *which capabilities the application may use*. This proposal
lets the build state exactly that.

## Proposal

### Overview

An injection specification attaches to one deferred function `d`, the
*injection target*. It consists of:

- a set of **bindings**, each of which binds one context parameter either to a
  factory function (a **provider**) or to a literal value (a **literal
  binding**), and
- an **allow list**, which states the full authority of the implementation of
  `d`.

Normally, resolving `d` yields its implementation `impl(d)`: an explicit
`--link` target, a default implementation, or, for `jo.main`, the
automatically located `main`. With an injection specification, `d` instead
resolves to a compiler-generated wrapper around `impl(d)`. Every other deferred
function resolves as before.

Entry-point discovery for `jo.main` currently looks for a top-level `main`
that conforms to the signature of `jo.main`. When `jo.main` is the injection
target, discovery is by name alone, and conformance is checked as described
under [Conformance](#conformance).

The mechanism injects context parameters. Some of them are capabilities, such
as a file system, and some are plain configuration, such as a repository name.

### Factories

A factory is a function that takes no arguments, written with or without an
empty parameter list, and has no type parameters. Its result type must conform
to the type of the context parameter it provides. It may receive context
parameters, which are satisfied by other bindings or by the context parameters
of `d`. The context parameters of a factory are those it declares in its
`receives` clause or, when the clause is omitted, those the compiler infers.

Factories and the context parameters named in bindings and in the allow list
must be accessible from the linking context. Naming a `private` definition is
an error.

A factory may be a deferred function. It then resolves through the ordinary
link rules, so the implementation of a capability can be chosen with `--link`.

The wrapper is generated at link time. A factory may therefore live in a link
library that application code cannot import.

### Literal bindings

A literal binding binds a context parameter whose declared type is exactly
`String`, `Int`, or `Bool`. The declared type determines how the value is
read, as described under [Command-line options](#command-line-options) and
[Build spec](#build-spec). A context parameter of any other type, including a
union of these types, cannot be bound to a literal, and a value that does not
match the declared type is an error. `Float` is excluded because its textual
form raises precision and locale questions that configuration should not
depend on.

Literal bindings let factories take configuration without value parameters. A
factory that needs a repository name receives it as a context parameter:

```jo
param repo: String

def GitHub(): Client receives repo = new GitHubClient(repo)
```

Secrets should not be bound as literals, since literals are fixed at compile
time and usually committed with the build spec. A factory can read them at run
time from a context parameter that grants access to the environment.

### The binding graph

Let `P` be the set of context parameters with a binding. Each parameter in `P`
is bound exactly once, and no parameter in `P` is a context parameter of `d`.

The bindings form a graph. A provider for `p` depends on the binding of `q`
when its factory receives `q` and `q` is in `P`. The graph must be acyclic.
Every context parameter a factory receives must be either in `P` or among the
context parameters of `d`. Every parameter in the allow list must likewise be
in `P` or among the context parameters of `d`.

Bindings are instantiated in topological order. Ties are broken by the textual
order of the entries, on the command line or in `jo.toml`. The build-spec
parser must preserve that order.

Each binding is instantiated exactly once per invocation of `d`. Two factories
that receive the same parameter see the same value.

### The generated wrapper

The wrapper has the signature of `d`, including its type parameters. Its body
instantiates the bindings in the order given above, then calls `impl(d)`
inside the allow list:

```jo
// conceptually, for the example in the summary
def main$injected: Unit receives IO.stdout, IO.stderr, IO.args =
  with fs.root = fs.Root() in
  with fs.readonly = fs.ReadOnly() in
  with gh.repo = "typescope/jo" in
  with gh.client = gh.GitHub() in
    allow fs.readonly, gh.client, IO.stdout in app.main
```

The arguments of `d` are passed to `impl(d)` unchanged. Every binding is
instantiated, whether or not the allow list depends on it.

Factories run outside the `allow` block, so a factory may receive a parameter
that the allow list omits. In the example, `fs.ReadOnly` receives `fs.root`,
while `app.main` may use only `fs.readonly`. The narrowing is as strong as the
type of the narrowed parameter. A factory that returns the broad capability
itself, where its type permits, hands it over unchanged. Keeping the view and
the broad capability distinct is the library author's responsibility.

The wrapper is not visible to source code.

### Conformance

Without injection, `impl(d)` must conform to the signature of `d`. With
injection, `impl(d)` must conform to the signature of `d` with its `receives`
clause replaced by the allow list. This reuses the existing link check. The
`allow` block in the wrapper covers the remaining case, a transitive use of a
parameter through a function whose context parameters are inferred.

### Command-line options

| Option | Meaning |
|---|---|
| `--inject d` | Attach the injection specification to deferred function `d`. |
| `--provide p=f` | Bind context parameter `p` to the result of calling factory `f`. |
| `--bind p=v` | Bind context parameter `p` to the literal value `v`. |
| `--allow p1,p2,...` | The context parameters `impl(d)` may use. `--allow none` permits none. |

`--inject` and `--allow` may each appear at most once. `--provide` and
`--bind` may be repeated. `p`, `f`, and `d` are qualified paths.

`--inject` is required when any of `--provide`, `--bind`, or `--allow` is
present, and `--allow` is required when `--inject` is present. The build
command therefore always names the injection target and the authority granted
to it.

The value of `--bind` is the text after the first `=`. It is read according to
the declared type of `p`:

| Type of `p` | Value |
|---|---|
| `String` | the text, unchanged, with surrounding whitespace preserved |
| `Int` | a decimal integer |
| `Bool` | `true` or `false` |

A module whose `compile-options` contain `--inject` and whose `jo.toml` also
declares an `inject` table is an error.

### Build spec

An app module may declare the same specification in `jo.toml`:

```toml
[module.app.inject]
target = "jo.main"
allow  = ["fs.readonly", "gh.client", "IO.stdout"]

[module.app.inject.provide]
"fs.root"     = "fs.Root"
"fs.readonly" = "fs.ReadOnly"
"gh.client"   = "gh.GitHub"

[module.app.inject.bind]
"gh.repo" = "typescope/jo"
```

`target` and `allow` are required. `provide` and `bind` default to empty.
`allow = []` permits no context parameters. Like `links`, the table is valid
only on app modules.

Keys in `provide` and `bind` are qualified paths and must be quoted. An
unquoted `fs.root` is a nested TOML table, not a path, and is an error.

A `bind` value must be a TOML string, integer, or boolean matching the
declared type of the parameter. Values are not converted, so `"30"` bound to
an `Int` parameter is an error.

#### Module dependencies

Unlike `links`, the `inject` table is not copied to modules that depend on
this one. It describes the composition root of one program, and a dependent
app module declares its own.

### Diagnostics

Violations of the rules above are errors. They point at the command-line
option or the `jo.toml` entry that introduced the problem, not at the
generated wrapper. The `allow` node of the wrapper carries a synthetic
position that names the option or entry, so the existing `allow` error trace
points there as well.

The following are warnings:

- a binding that nothing in the allow list depends on
- an injection target that nothing in the program calls (`jo.main` counts as
  called)

## Example

The process-monitor demo no longer needs a platform layer of its own. The API
library additionally declares `param alertScript: String` and
`param intervalSecs: Int`, and the platform library ships factories instead of
`platformMain`:

```jo
namespace SystemRuntime

def Process(): SystemAPI.Process = new ProcessImpl()
def System(): SystemAPI.System = new SystemImpl()
def Logger(): SystemAPI.Logger receives IO.stdout = new LoggerImpl(IO.stdout)
def Timer(): SystemAPI.Timer = new TimerImpl()
def Alerter(): SystemAPI.Alerter receives SystemAPI.alertScript = new AlerterImpl(SystemAPI.alertScript)
```

The application implements `main` with the capabilities it needs:

```jo
def main: Unit receives IO.stdout, process, system, logger, timer, alerter, intervalSecs = ...
```

The original `platformMain` read the interval from an environment variable.
Reading the environment needs an environment capability, and `jo.main` does
not offer one, so the interval becomes a literal binding instead.

The build wires them:

```bash
jo compile --python UserApp.jo \
  --lib out/api --link-lib out/runtime \
  --inject  jo.main \
  --provide SystemAPI.process=SystemRuntime.Process \
  --provide SystemAPI.system=SystemRuntime.System \
  --provide SystemAPI.logger=SystemRuntime.Logger \
  --provide SystemAPI.timer=SystemRuntime.Timer \
  --provide SystemAPI.alerter=SystemRuntime.Alerter \
  --bind    SystemAPI.alertScript=./alert.sh \
  --bind    SystemAPI.intervalSecs=30 \
  --allow   IO.stdout,SystemAPI.process,SystemAPI.system,SystemAPI.logger,SystemAPI.timer,SystemAPI.alerter,SystemAPI.intervalSecs \
  -o out/monitor.py
```

A capability from a second library is one more `--provide` and one more
allow-list entry.

## Justification

### Why attach injection to a deferred function?

Jo has no special entry point. `jo.main` is an ordinary deferred function that
the backend happens to call. Deferred functions are already the place where a
build wires a program together, so injection belongs there too.

Calls to a deferred function go through the deferred symbol, so no call sites
need rewriting. Code that calls `impl(d)` directly still sees its declared
signature. Recursion, shadowing, and test doubles keep their ordinary meaning.

Injecting into arbitrary functions would instead require rewriting call sites
selectively, and would silently replace a capability the caller had already
bound.

### Why one injection target?

One target gives the program a single composition root, where an auditor can
read every capability the program creates and the authority it grants. The
specification is defined per deferred function, so allowing several targets
later would not change its meaning. Since bindings live for one invocation of
their target, several targets would not share instances.

### Why name the target explicitly?

The target is named by its deferred function, not by a link, because the
implementation may come from a default or from entry-point discovery rather
than from `--link`. Attaching options to the preceding `--link` would make the
meaning of a build command depend on option order.

`--inject` has no default. Defaulting to `jo.main` would give it a special role
that the language otherwise avoids, and naming it costs one option.

### Why literal bindings instead of factory arguments?

Factory arguments would need positional values, a way to type-check them
against the factory signature, and pressure to accept expressions rather than
literals. Binding literals to context parameters reuses an existing
mechanism. Configuration is named, typed by its parameter declaration, and
visible in the signature of every factory that reads it.

The cost is that one parameter has one value per injection. Two instances of a
factory cannot read different values from the same parameter. Libraries can
declare distinct parameters when they need distinct configuration.

### Why an allow list?

Authority narrowing requires creating a capability that the target must not
see. In the summary example, `fs.ReadOnly` receives `fs.root`, but `app.main`
may use only `fs.readonly`, and the `allow` check guarantees that no helper
function reaches `fs.root` on its behalf.

Marking individual bindings as hidden would achieve this for bindings, but not
for the context parameters of `d` itself, such as `IO.stdout`, which have no
binding to mark. An allow list covers both, states the full authority of the
target in one place, and is enforced by the existing `allow` construct.

The allow list is required so that the authority of the target is always
written down. Omitting it would grant everything in scope by default.

### Why may a binding not rebind a parameter of `d`?

If `P` could contain a context parameter of `d`, the factories and `impl(d)`
could each see either the outer value or the injected one, and the
specification would have to say which. Keeping the two sets disjoint avoids
the question. Wrapping an existing capability is left to future work.

### Why not a Jo snippet?

A Jo snippet that can contain expressions is ordinary code, and ordinary code
is the glue this proposal removes. A snippet restricted to `with` bindings and
factory calls would be as declarative as the options, but it would still need
a file, a namespace, and a place in the build. It would also have to be
compiled in the linking context, where link libraries are visible, which is
not a context in which user code is otherwise compiled. The value of the
feature is a declarative specification that names factories and literals only.
The command-line form follows the style of `--link`, and the `jo.toml` form
keeps the specification checked in alongside `links`.

### Why instantiate per invocation?

A binding lives for one invocation of `d`. For `jo.main` that is the whole
program, so each capability has a single instance without introducing global
state.

## Compatibility

The proposal adds four compiler options and one `jo.toml` table. Builds that
use none of them are unchanged: deferred functions resolve as before, and the
conformance rule for links is unchanged.

No language syntax changes. The generated wrapper uses existing constructs:
`with`, `allow`, and ordinary calls. The SAST format is unchanged.

The comment on `jo.main` in `lib/Main.jo` says that its `receives` clause is
an upper bound for the entry point. Under injection the entry point may
receive more, so the comment must be updated.

## Future work

- **Wrapping an existing capability.** A binding cannot currently rebind a
  context parameter of `d`, for example to prefix everything written to
  `IO.stdout`.
- **Cleanup.** Factories that open resources have no disposal hook. A future
  proposal could close bindings in reverse topological order when `d`
  returns.
- **Per-provider configuration.** A provider could bind parameters seen only by
  its own factory, so that one factory can be instantiated twice with
  different configuration.

## Related documentation

- [Context Parameters](../language/definitions/context-parameters.md), for
  `param`, `with`, `receives`, and `allow`
- [Deferred Functions](../language/definitions/deferred-functions.md), for
  `defer def` and `--link`
- [Build Spec Reference](../usage/reference/build-spec.md), for `links` and
  app modules
- [Capabilities](../overview/capabilities.md), for the security model this
  proposal serves
