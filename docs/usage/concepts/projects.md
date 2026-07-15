# Projects

A Jo project is a directory containing a build spec (`jo.toml`) and one or more ordered modules under `[module.<id>]`.

## Modules

Modules are either libraries or apps:

```toml
jo = "1.0"
default = "app"

[module.api]
kind = "lib"
src = ["api/src/"]

[module.app]
kind = "app"
src = ["app/src/"]
platform = "python"
dependencies = [
  { module = "api" },
]
```

Every app module declares its own `platform` — the backend it emits. Lib modules compile to `.sast`, which is backend-independent, so they default to `platform = "pure"` and only name a platform when their output requires one.

## Depending On An App Module

An app module is a lib module plus a platform, link wiring, and an entry point. Other modules can depend on one, which is what makes a test module possible:

```toml
[module.test]
kind = "app"
platform = "python"
src = ["tests/"]
dependencies = [
  { module = "app" },
]
```

The test sees the app's code. It does not inherit the app's entry point, because Jo takes the entry point from the sources of the module being built. It does inherit the app's links, which it may override, and its link libraries, which it may not. See [Testing](../guides/testing.md).

If `default` is omitted, the first module in the file is the default for commands such as `jo build`, `jo run`, `jo doc`, `jo deps`, and `jo package`.

## Build Output Layout

Build artifacts are keyed by module id:

```
.build/<module>/
  jo-<version>/
    sast/
    target/    # app modules only
  release/     # jo package
  doc/         # jo doc
```

## Publishable Modules

Any module can be publishable by defining `[module.<id>.package]`:

```toml
[module.api.package]
name = "agent-api"
version = "1.0.0"
license = "MIT"
```

## External Source Projects

A module can depend on a module from another project:

```toml
dependencies = [
  { path = "../agent-api", module = "api" },
]
```

Jo reads the external project's `jo.toml` to find that module's sources and dependencies, then builds it as part of this build: one resolution, one `jo.lock`, one compiler. The external project's own `jo.lock` applies only when that project is built on its own.
