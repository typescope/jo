# Packages

A Jo package is a `.joy` file — a tar archive containing compiled `.sast` files and a `meta.toml` describing the package.

## The `.joy` Format

```
agent-api-v1.2.0.joy
  meta.toml          # package metadata
  agentapi/
    AgentAPI.sast    # one .sast per source file
    QueryDSL.sast
```

`.sast` files are target-independent in format — the same `.joy` serves all backends (Python and Ruby).

## Check Libraries and Link Libraries

When a package appears in `[main.dependencies]`, it can play one of two roles:

**Check library** (default) — available for type checking. User code can import its namespaces, use its types, and call its functions.

**Link library** (`link = true`) — used only at link time to resolve `defer def`s. Hidden from user code; its namespaces cannot be imported.

```toml
[main.dependencies]
agent-api            = "^1.0"                        # check library
agent-runtime-python = { version = "^1.0", link = true }  # link library
```

This separation prevents user code from accidentally depending on platform-specific implementation details.

## Deferred Definitions

A library can expose extension points using `defer def`:

```jo
namespace agentapi

defer def runTask(input: String): String
```

The app wires implementations at link time via `[main.links]`:

```toml
[main.links]
"agentapi.runTask" = "usertask.runTask"
```

All wiring is explicit — there is no auto-inference. If any `defer def` is unresolved at link time, the build fails with a clear error.

For tests, `[test.links]` is merged with `[main.links]`, allowing specific `defer def`s to be overridden with mock implementations:

```toml
[test.links]
"agentapi.runTask" = "mocks.fakeRunTask"
```

## FFI and Platform Packages

The `ffi` field in `meta.toml` indicates whether a package uses platform-specific FFI:

| Value      | Meaning                                      |
|------------|----------------------------------------------|
| `"none"`   | Platform-independent — works on all backends |
| `"python"` | Uses Python FFI                              |
| `"ruby"`   | Uses Ruby FFI                                |

`ffi` is **contagious**: if any dependency has `ffi != "none"`, the package inherits that value. Two dependencies with conflicting `ffi` values is a build error.

A library author can assert platform-independence in `jo.toml`:

```toml
[package]
ffi = "none"    # build error if any dep introduces FFI
```

If omitted, `ffi` is computed automatically from source and dependencies.
