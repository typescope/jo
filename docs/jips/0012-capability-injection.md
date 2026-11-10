---
author: Fengyun Liu
status: Draft
created: 2026-11-09
title: Capability injection
---

# JIP 0012 — Capability injection

<JipMeta />

## Summary

Today, a program that needs capabilities beyond those of `jo.main` must write
its own entry point to provision them. This proposal adds a compiler feature
that removes that boilerplate. Each option below names a context parameter and
says how to provision its value, either by calling a factory function or from
a literal:

```bash
jo compile --python app.jo \
  --internal fs.root=fs.Root \
  --provide  fs.readonly=fs.ReadOnly \
  --internal 'gh.repo="typescope/jo"' \
  --provide  gh.client=gh.GitHub
```

The compiler creates the values, binds them, and calls the program's `main`
with the bindings in scope. A `--provide` parameter is visible to `main`. An
`--internal` parameter is visible only to the factories. In the example,
`main` may use the read-only file system but not the root file system it was
built from.

The proposal is purely additive. Programs and builds that do not use the new
options are unaffected.

## Motivation

The entry point `jo.main` is a deferred function with a fixed signature:

```jo
defer def main: Unit receives IO.stdout, IO.stderr, IO.args
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

The glue also serves the purpose of narrowing authority. A platform may open
a file system, wrap it in a read-only
view, and hand only the view to the application. The broad capability exists
in the program but never reaches the untrusted code.

The glue carries no information beyond *which factory creates which
capability* and *which capabilities the application may use*. This proposal
introduces a compiler feature that lets a build specify the capabilities as
compilation options. The compiler synthesizes the glue.

## Proposal

### Overview

An injection specification attaches to one deferred function `d`, the
*injection target*. In this proposal `d` is always `jo.main`. The rules are
written for a general `d` so that a later proposal can add other targets
without changing them.

The specification consists of a set of **bindings**, one per compiler option:

```bash
--internal fs.root=fs.Root
--provide  fs.readonly=fs.ReadOnly
--internal 'gh.repo="typescope/jo"'
--provide  gh.client=gh.GitHub
```

Each binding binds one context parameter to a value, which is either the
result of a **factory** function, such as `fs.Root`, or a **literal**, such as
`"typescope/jo"`, and gives the parameter one of two roles:

- a **provided** parameter is available to the implementation of `d` and to
  the factories,
- an **internal** parameter is available to the factories only.

The implementation of `d` may therefore use the provided parameters and the
context parameters of `d`, and nothing else. In the example, it may use
`fs.readonly` and `gh.client` but neither `fs.root` nor `gh.repo`.

The implementation of `d`, written `impl(d)`, is found as it is today,
through `--link`. With an injection
specification, `d` resolves to a compiler-generated wrapper around `impl(d)`
instead of to `impl(d)` itself. Every other deferred function resolves as
before.

The mechanism injects context parameters. Some of them are capabilities, such
as a file system, and some are plain configuration, such as a repository name.

### Factories

A factory is a function that takes no arguments and has no type parameters. Its result type must conform
to the type of the context parameter it provides. It may receive context
parameters, which are satisfied by other bindings or by the context parameters
of `d`:

```jo
namespace fs

param root: FileSystem
param readonly: ReadOnlyFileSystem

def Root(): FileSystem = new LocalFileSystem()
def ReadOnly(): ReadOnlyFileSystem receives root = new ReadOnlyView(root)
```

`Root` creates a capability from nothing. `ReadOnly` receives `root`, which
the binding `--internal fs.root=fs.Root` satisfies, and narrows it to a
read-only view. The context parameters of a factory are those it declares in
its `receives` clause or, when the clause is omitted, those the compiler
infers.

The paths in bindings are resolved as the paths in `--link` are. Visibility
rules do not apply.

The wrapper is generated at link time. A factory may therefore live in a link
library that application code cannot import.

### Literals

The value of a binding may be a Jo literal instead of a factory. The literal
is read by Jo's lexer and checked as the initializer of a `val` of the
parameter's type, exactly as `val x: T = e` would be. Every type with a
literal form is therefore bindable: `Bool`, `Int`, `Long`, `Byte`, `Char`,
`Float`, and `String`. A parameter of any other type has no literal form and
cannot be bound to a literal.

A literal is never mistaken for a factory path. A path starts with a letter or
an underscore and continues with identifiers and dots. A literal starts with a
digit, a sign, or a quote, or is one of the keywords `true` and `false`.

Literals let factories take configuration without value parameters. A factory
that needs a repository name receives it as a context parameter:

```jo
param repo: String

def GitHub(): Client receives repo = new GitHubClient(repo)
```

Secrets should not be bound as literals, since literals are fixed at compile
time and usually committed with the build spec. A factory can read them at run
time from a context parameter that grants access to the environment.

### The binding graph

Let `P` be the set of context parameters with a binding. Each parameter in `P`
is bound exactly once. Every context parameter a factory receives must be
either in `P` or among the context parameters of `d`.

Factories receive the context parameters of `d` at their outer values, as the
right-hand sides of a `with` are evaluated in the enclosing scope. They
receive every other bound parameter at its bound value.

The bindings form a graph. The binding of `p` depends on the binding of `q`
when the factory of `p` receives `q` and `q` is in `P` but not among the
context parameters of `d`. The graph must be acyclic. In particular, a
factory that receives the parameter it binds forms a cycle unless that
parameter is a context parameter of `d`, in which case it receives the outer
value.

A `--provide` binding may therefore rebind a context parameter of `d`. For
example, a factory that receives `IO.stdout` and returns a function that
prefixes each line before writing it can be bound to `IO.stdout` itself. The
rebinding is seen by `impl(d)` only, since factories receive the outer value.

An `--internal` binding may not rebind a context parameter of `d`. The
implementation of `d` may use every parameter `d` declares, so an internal
rebinding could not keep its value from the implementation.

Each binding is instantiated at most once per invocation of `d`, in
topological order. The order among independent bindings is unspecified.

### The generated wrapper

The wrapper has the signature of `d`, including its type parameters. Its body
instantiates the bindings in the order given above, binds each to its
parameter, and calls `impl(d)` with the arguments of `d` unchanged:

```jo
// conceptually, for the example in the summary
def main$injected: Unit receives IO.stdout, IO.stderr, IO.args =
  with fs.root = fs.Root() in
    with fs.readonly = fs.ReadOnly() in
      with gh.repo = "typescope/jo" in
        with gh.client = gh.GitHub() in
          app.main
```

Internal parameters are in scope when `impl(d)` runs, but the
[conformance](#conformance) check guarantees that `impl(d)` does not use
them. In the example, `fs.ReadOnly` receives `fs.root`, while `app.main` may
use only `fs.readonly`.

The wrapper is not visible to source code.

### Conformance

Without injection, `impl(d)` must conform to the signature of `d`. With
injection, the rule for context parameters is relaxed: `impl(d)` may also
receive any provided parameter. The context parameters of `impl(d)` are those
it declares or, when the clause is omitted, those the compiler infers. They
must all be among the context parameters declared by `d` or the parameters
bound by `--provide`. The rest of the signature is checked as before.

### Command-line options

| Option | Meaning |
|---|---|
| `--provide p=e` | Bind context parameter `p` to `e` as a provided parameter. |
| `--internal p=e` | Bind context parameter `p` to `e` as an internal parameter. |

Both options attach their binding to `jo.main`, whose implementation is
found as it is today.

`e` is either a qualified path naming a factory or a Jo literal. `--provide`
and `--internal` may be repeated. `p` and a factory path are qualified paths.
The value `e` is the text after the first `=`.

A string literal needs its double quotes to reach the compiler, so on the
command line the whole option value is quoted for the shell:

```bash
--internal 'gh.repo="typescope/jo"'
```

A project declares the same options in `jo.toml` through the existing
`compile-options` field of its app module, one option or value per array
element. A TOML literal string, delimited by single quotes, carries a Jo
string literal without escapes:

```toml
[module.app]
kind = "app"
platform = "python"
compile-options = [
  "--internal", "fs.root=fs.Root",
  "--provide",  "fs.readonly=fs.ReadOnly",
  "--internal", 'gh.repo="typescope/jo"',
  "--provide",  "gh.client=gh.GitHub",
]
```

`compile-options` belong to one module and are not copied to modules that
depend on it, so each app module declares its own composition root. This
proposal adds no field to the build spec. A dedicated table is left to
[future work](#future-work).

### Diagnostics

Violations of the rules above are errors. As with `--link`, an error in a
binding has no source position. Its message quotes the option, for example
`--provide fs.readonly=fs.ReadOnly`, and never mentions the generated
wrapper.

An internal parameter that no factory receives is a warning.

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
not offer one, so the interval becomes a literal instead.

The build wires them:

```bash
jo compile --python UserApp.jo \
  --lib out/api --link-lib out/runtime \
  --provide  SystemAPI.process=SystemRuntime.Process \
  --provide  SystemAPI.system=SystemRuntime.System \
  --provide  SystemAPI.logger=SystemRuntime.Logger \
  --provide  SystemAPI.timer=SystemRuntime.Timer \
  --provide  SystemAPI.alerter=SystemRuntime.Alerter \
  --internal 'SystemAPI.alertScript="./alert.sh"' \
  --provide  SystemAPI.intervalSecs=30 \
  -o out/monitor.py
```

Only the `Alerter` factory reads `alertScript`, so it is internal, and `main`
cannot name it. A capability from a second library is one more `--provide`.

## Justification

### Why attach injection to a deferred function?

Jo has no special entry point. `jo.main` is an ordinary deferred function that
the runtime calls. Deferred functions are already the place where a
build wires a program together, so injection belongs there too.

Calls to a deferred function go through the deferred symbol, so no call sites
need rewriting. Code that calls `impl(d)` directly still sees its declared
signature. Recursion, shadowing, and test doubles keep their ordinary meaning.

Injecting into arbitrary functions would instead require rewriting call sites
selectively, and would silently replace a capability the caller had already
bound.

### Why only `jo.main`?

One target gives the program a single composition root, where an auditor can
read every capability the program creates and the authority it grants.
`jo.main` is the natural one: the compiler already treats it specially, as
the one deferred function whose implementation is located automatically, and
nearly every injection would target it.

Fixing the target also keeps the options minimal. An option to select the
target would need rules for how it interacts with `--link`, which names the
implementation. With `jo.main` as the only target, the implementation is
found as it is today and no such rules are needed.

The rules are written for a general `d`, and other targets can be added by
extending the binding syntax, as sketched under
[future work](#future-work), without changing the semantics.

### Why literals instead of factory arguments?

Factory arguments would need positional values, a way to type-check them
against the factory signature, and pressure to accept expressions rather than
literals. Binding literals to context parameters reuses an existing
mechanism. Configuration is named, typed by its parameter declaration, and
visible in the signature of every factory that reads it.

The cost is that one parameter has one value per injection. Two instances of a
factory cannot read different values from the same parameter. Libraries can
declare distinct parameters when they need distinct configuration.

### Why not a Jo snippet?

A Jo snippet that wires the capabilities is ordinary code, and ordinary code
is the glue this proposal removes. It is also more involved than it looks.
The snippet calls factories that use the FFI, so it belongs to the trusted
world and must be compiled as a library with FFI enabled. The user program
belongs to the confined world, so the snippet cannot call it directly: it
needs a deferred entry point declared in a pure API library, which the user
program implements and the build links. That is the platform layer of the
process-monitor demo, compiled in three stages. The options describe the same
wiring in a few lines, follow the style of `--link`, and are kept in
`compile-options` alongside `links`.

### Why instantiate per invocation?

A binding lives for one invocation of `d`. For `jo.main` that is the whole
program, so each capability has a single instance without introducing global
state.

## Compatibility

The proposal adds two compiler options and no build-spec fields. Builds that
use none of them are unchanged: deferred functions resolve as before, and the
conformance rule for links is unchanged.

No language syntax changes. The generated wrapper uses existing constructs:
`with` and ordinary calls. The SAST format is unchanged.

The comment on `jo.main` in `lib/Main.jo` says that its `receives` clause is
an upper bound for the entry point. Under injection the entry point may
receive more, so the comment must be updated.

## Future work

- **Other targets and multiple targets.** This proposal is intentionally
  restricted to `jo.main`. A later one could let a binding name the deferred
  function it attaches to, as in
  `--provide SystemAPI.process@foo=SystemRuntime.Process`, with `@global`
  meaning every deferred function that receives the parameter. A binding
  without `@` would keep attaching to `jo.main`.
- **Cleanup.** Factories that open resources have no disposal hook. A future
  proposal could close bindings in reverse topological order when `d`
  returns.
- **Per-factory configuration.** A binding could bind parameters seen only by
  its own factory, so that one factory can be instantiated twice with
  different configuration.
- **A dedicated `jo.toml` table.** Once the options have seen use, a
  structured form could replace the `compile-options` array, for example:

  ```toml
  [module.app.inject.provide]
  "fs.readonly" = "fs.ReadOnly"
  "gh.client"   = "gh.GitHub"

  [module.app.inject.internal]
  "fs.root" = "fs.Root"
  "gh.repo" = '"typescope/jo"'
  ```

  Such a table would have to settle the quoting of dotted keys, which TOML
  otherwise reads as nested tables.

## Related documentation

- [Context Parameters](../language/definitions/context-parameters.md), for
  `param`, `with`, and `receives`
- [Deferred Functions](../language/definitions/deferred-functions.md), for
  `defer def` and `--link`
- [Build Spec Reference](../usage/reference/build-spec.md), for `links`,
  `compile-options`, and app modules
- [Basic Types](../language/types/basic-types.md), for the literal forms
- [Capabilities](../overview/capabilities.md), for the security model this
  proposal serves
