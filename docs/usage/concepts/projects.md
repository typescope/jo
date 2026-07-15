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
target = "python"
dependencies = [
  { module = "api" },
]
```

Every app module declares its own `target`. Lib modules have no target — they compile to `.sast`, which is backend-independent.

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
