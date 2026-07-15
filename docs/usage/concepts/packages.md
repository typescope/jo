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
packages = [{ name = "agent-api", version = "1.0" }]
```

**Link library** (`link = true`) — used only at link time to resolve
`defer def`s. It is hidden from user code. Its namespaces cannot be imported.

```toml
packages = [{ name = "agent-runtime-python", version = "1.0", link = true }]
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

## Platform-Bound Packages

A package's `meta.toml` records whether it is pure Jo or tied to a platform. The
field is called `runtime` there, because `meta.toml` is generated rather than
written — see [Library Metadata](../reference/library-metadata.md):

| Value      | Meaning                     |
|------------|-----------------------------|
| `"pure"`   | Pure Jo                     |
| `"python"` | Requires the Python runtime |
| `"ruby"`   | Requires the Ruby runtime   |

A package author declares it on the module, not on the package table:

```toml
[module.runtime]
kind = "lib"
platform = "python"
enable-ffi = true

[module.runtime.package]
name = "agent-runtime-python"
version = "1.0.0"
```

`platform` states what the package requires. `enable-ffi` is separate and off by
default — it grants *this* module's code access to `py.*`, which a thin FFI
adapter needs and its dependents do not.

Lib modules default to `"pure"`. App modules always name a platform, so a
published app module records the one it is built for. `jo package` derives the
`meta.toml` `runtime` from the module's `platform`, so it cannot disagree with
what was compiled.

`platform` is **contagious** through the dependency graph. If a source module
dependency is not `"pure"`, the dependent module is bound to that platform too.
Two dependencies with conflicting platforms are a build error.

Published package dependencies must be `pure`. Platform-bound packages are
meant to be thin adapter packages at the edge of the graph, not deep transitive
layers.
