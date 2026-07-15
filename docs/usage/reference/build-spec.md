# Build Spec Reference

The build spec is a TOML file (`jo.toml` by default) that describes one project. A project contains one or more ordered modules under `[module.<id>]`.

## Top-Level Fields

| Field     | Type    | Required | Description |
|-----------|---------|----------|-------------|
| `jo`      | string  | yes      | Compiler compatibility line, e.g. `"1.0"`. Uses `MAJOR.MINOR` format. |
| `default` | string  | no       | Default module id. If omitted, the first `[module.<id>]` section is the default. |

Module declaration order is part of the spec's meaning: it selects the default module when `default` is absent, and it fixes the order of diagnostics.

There is no project-level `depth`. Depth is declared per module — see [Dependency Depth](../concepts/dependency-depth.md).

## `[pinning]` — Root-Only Exact Overrides

`[pinning]` lets the root project force an exact package version when normal
compatibility-line resolution needs a manual override.

```toml
[pinning]
mustache = "1.2.3"
```

Rules:

- values use `MAJOR.MINOR.PATCH`
- pinning belongs at the top level of the root `jo.toml`
- `meta.toml` never contains pinning
- published packages never export pins transitively
- a pin is a hard requirement: if it conflicts with dependency constraints, Jo fails explicitly

This is mainly intended for apps and top-level builds. Libraries should continue
to declare normal compatibility constraints only.

## `[module.<id>]`

Module ids must start with a letter and may contain letters, digits, and hyphens.

| Field             | Type             | Required | Description |
|-------------------|------------------|----------|-------------|
| `kind`            | string           | yes      | `"lib"` or `"app"`. |
| `src`             | array of globs   | no       | Source files. Default: `["<id>/src/**/*.jo"]`. |
| `target`          | string           | app only | Backend: `"python"` or `"ruby"`. Required for `kind = "app"`, invalid for `kind = "lib"`. |
| `depth`           | integer          | no       | Maximum registry package dependency depth for this module. Default: `0` for `lib`, `1` for `app`. |
| `compile-options` | array of strings | no       | Extra flags passed verbatim to `jo compile` for this module. |
| `dependencies`    | array of tables  | no       | Registry or source module dependencies. |
| `links`           | array of tables  | no       | Explicit `defer def` wiring. |

Every app module states its own `target`. There is no default and no inheritance from another module, so a test module in a Ruby project declares `target = "ruby"` like any other app module.

Example:

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
  { package = "mustache", version = "1.0" },
]
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]
```

## Dependencies

Registry packages require a compatibility line:

```toml
dependencies = [
  { package = "mustache", version = "1.0" },
]
```

Same-project source modules use only a module id and never a version:

```toml
dependencies = [
  { module = "api" },
]
```

External project source modules use `path` plus a module id. Jo loads `path/jo.toml` for that module's sources and dependencies. It resolves them together with this project's own. The external project's packages are locked in this project's `jo.lock`. Its own `jo.lock` is ignored. See [Dependency Resolution](dependency-resolution.md).

```toml
dependencies = [
  { path = "../agent-api", module = "api" },
]
```

Add `link = true` for link-only dependencies. Link dependencies are hidden from user code and are omitted from generated package metadata.

## Links

Explicit wiring of `defer def`s:

```toml
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]
```

## `[module.<id>.package]`

Any module can be publishable by adding a nested package section.

| Field         | Type            | Required | Description |
|---------------|-----------------|----------|-------------|
| `name`        | string          | yes      | Package name. Must start with a letter and may contain letters, digits, and hyphens. |
| `version`     | string          | yes      | Package version in `MAJOR.MINOR.PATCH` format. |
| `runtime`     | string          | no       | Optional assertion: `"pure"`, `"python"`, or `"ruby"`. Non-`pure` values add the matching runtime API compile option. |
| `description` | string          | no       | Package description. |
| `authors`     | array of string | no       | Package authors. |
| `homepage`    | string          | no       | Homepage URL. |
| `license`     | string          | no       | License identifier. |
| `keywords`    | array of string | no       | Search keywords. |

```toml
[module.api.package]
name = "agent-api"
version = "1.2.0"
runtime = "pure"
```

When packaging, direct registry dependencies are recorded in `meta.toml`. Direct source module dependencies are recorded by their package name and version line, unless `link = true`.

## `[doc]`

Documentation settings apply to `jo doc <module>`.

```toml
[doc]
title = "Agent API"
readme = "README.md"
include-private = false
include-source = false
```

## `[commands]`

Named shell commands invoked as `jo <name>`:

```toml
[commands]
dev = "jo build app && jo run app"
fmt = "jo fmt src"
```

Built-in commands always win. Use `jo exec <name>` to run a defined command unambiguously.
