# Writing FFI Packages

An FFI package bridges Jo code to a specific platform, such as Python or Ruby.

## Declaring a Platform

An FFI module sets two fields:

```toml
[module.runtime]
kind = "lib"
src = ["src/"]
platform = "python"
enable-ffi = true

[module.runtime.package]
name = "agent-runtime-python"
version = "1.0.0"
```

`platform = "python"` says the module's output requires Python. It is what the
generated `meta.toml` records, so consumers know the package needs Python, and
it is what spreads to dependents through contagion.

`enable-ffi = true` says this module's own code may call `py.*`. It adds the
matching FFI API while compiling the module, the same thing
`jo compile --use-runtime-api python` does.

Both live on the module, not on the package table. A module does not have to be
publishable to use FFI — an internal lib module or a test app module declares
them the same way.

## FFI Is Off By Default

Naming a platform does not open the FFI surface. `enable-ffi` is `false` unless
you ask, so this module requires Python but cannot call `py.*`:

```toml
[module.core]
kind = "lib"
platform = "python"
modules = ["runtime"]
```

`core` requires Python because the `runtime` module does, and its `meta.toml`
records that. Its own source still cannot reach the runtime. That is the point of
an FFI package: the escape hatch stays in the thin adapter at the edge, and the
code above it is ordinary Jo.

Reaching a runtime is a capability, so Jo makes you grant it rather than
inherit it. `enable-ffi = true` on a `platform = "pure"` module is an error —
there is no FFI API to enable.

## Implementing Deferred Definitions

An FFI package commonly implements `defer def`s from an API package:

```toml
packages = [{ name = "agent-api", version = "1.0" }]
```

The app wires implementations through module links:

```toml
links = [
  { from = "agentapi.runTask", to = "agentruntime.runTask" },
]
```

## Platform Contagion

`platform` is contagious through source module dependencies. Any module that
depends on an FFI module inherits its platform requirement. An app depending on
a source module with `platform = "python"` must itself be a Python app.

Two source dependencies with conflicting platforms, one requiring `"python"` and
another requiring `"ruby"`, are a build error.

Published package dependencies must be `pure`. Platform-bound packages are meant
to stay at the edge of the graph, usually as thin FFI adapters.
