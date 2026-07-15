# Packages

A Jo package is a `.joy` archive containing compiled `.sast` files and a `meta.toml` file.

## The `.joy` Format

```
agent-api-v1.2.0.joy
  meta.toml          # package metadata
  agentapi/
    AgentAPI.sast    # one .sast per source file
    QueryDSL.sast
```

`.sast` files are target-independent in format, so the same `.joy` can serve
multiple backends.

## Check And Link Dependencies

When a module lists a dependency, it can play one of two roles:

**Check library** (default) — available for type checking. User code can import
its namespaces, use its types, and call its functions.

```toml
dependencies = [
  { package = "agent-api", version = "1.0" },
]
```

**Link library** (`link = true`) — used only at link time to resolve
`defer def`s. It is hidden from user code. Its namespaces cannot be imported.

```toml
dependencies = [
  { package = "agent-runtime-python", version = "1.0", link = true },
]
```

This separation prevents user code from accidentally depending on
platform-specific implementation details.

Link libraries are valid only on app modules, since only an app module produces
an executable to link. A module that depends on an app module inherits that
module's link libraries, and cannot override them.

## Deferred Definitions

A library can expose extension points using `defer def`:

```jo
namespace agentapi

defer def runTask(input: String): String
```

An app wires implementations explicitly:

```toml
links = [
  { from = "agentapi.runTask", to = "usertask.runTask" },
]
```

If any `defer def` is unresolved at link time, the build fails.

`links` belongs to app modules only. A module that depends on an app module
inherits its links and may override any of them by declaring a link with the
same `from`. See [Build Spec](../reference/build-spec.md).

## Runtime-Constrained Packages

The `runtime` field in `meta.toml` indicates whether a package is pure Jo or tied to a runtime:

| Value      | Meaning                     |
|------------|-----------------------------|
| `"pure"`   | Pure Jo                     |
| `"python"` | Requires the Python runtime |
| `"ruby"`   | Requires the Ruby runtime   |

A package author can assert runtime in `[module.<id>.package]`:

```toml
[module.runtime.package]
name = "agent-runtime-python"
version = "1.0.0"
runtime = "python"
```

If omitted, `runtime` defaults to `"pure"`.

`runtime` is **contagious** through the dependency graph: if a source module
dependency has `runtime != "pure"`, the dependent module is treated as requiring
that runtime too. Two dependencies with conflicting non-`pure` runtime values
are a build error.

Published package dependencies must be `pure`. Runtime-constrained packages are
meant to be thin adapter packages at the edge of the graph, not deep transitive
layers.
