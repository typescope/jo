# Writing FFI Packages

An FFI package bridges Jo code to a specific platform, such as Python or Ruby.

## Declaring Runtime

Set `runtime` in `[module.<id>.package]`:

```toml
[module.runtime]
kind = "lib"
src = ["src/"]

[module.runtime.package]
name = "agent-runtime-python"
version = "1.0.0"
runtime = "python"
```

For a library module, this adds the matching runtime API during compilation, equivalent to `jo compile --use-runtime-api python ...`.

## Implementing Deferred Definitions

An FFI package commonly implements `defer def`s from an API package:

```toml
dependencies = [
  { package = "agent-api", version = "1.0" },
]
```

The app wires implementations through module links:

```toml
links = [
  { from = "agentapi.runTask", to = "agentruntime.runTask" },
]
```

## Runtime Contagion

`runtime` is contagious through source module dependencies. Any module that
depends on an FFI module inherits its runtime requirement. An app depending on a
source module with `runtime = "python"` is treated as requiring the Python
runtime and is built for the Python target unless it explicitly selects a
compatible target.

Two source dependencies with conflicting runtime assertions, such as one
requiring `"python"` and another requiring `"ruby"`, are a build error.

Published package dependencies must be `pure`. Runtime-constrained packages are
meant to stay at the edge of the graph, usually as thin FFI adapters.
