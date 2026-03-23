# Build Spec Reference

The build spec is a TOML file (`jo.toml` by default) that describes how to build a project. Pass `--spec <file.toml>` to use a different file.

## Top-Level Fields

| Field  | Type   | Required | Description |
|--------|--------|----------|-------------|
| `jo`   | string | yes      | Compiler version constraint, e.g. `">=1.0"`. Uses `MAJOR.MINOR` format. |
| `name` | string | no       | Project name. Used to derive the output filename. Set by `jo new <name>`. Defaults to the spec filename stem if absent. Not valid for lib builds. |

## `[package]` — Library Build Options

Presence of this section marks the build as a **library**. Publishing metadata fields are described in [Library Metadata](library-metadata.md). The following fields affect the build:

| Field        | Type    | Required | Description |
|--------------|---------|----------|-------------|
| `ffi`        | string  | no       | Optional assertion: `"none"`, `"python"`, `"ruby"`. Verified by `jo build-release`. Computed from source and deps if absent. |
| `depth` | integer | no       | Maximum allowed dependency tree height. Default: `0`. Library authors who add dependencies must set this explicitly. See [Dependency Resolution](dependency-resolution.md). |

## `[main]` — Main Source

| Field        | Type           | Required | Description |
|--------------|----------------|----------|-------------|
| `src`        | array of globs | no       | Source files. Default: `["src/**/*.jo"]`. |
| `target`     | string         | no       | Backend: `"python"`, `"ruby"`. Default: `"python"`. |
| `depth` | integer        | no       | Maximum allowed dependency tree height. Default: `1` for apps. See [Dependency Resolution](dependency-resolution.md). |

## `[test]` — Test Source

| Field    | Type           | Required | Description |
|----------|----------------|----------|-------------|
| `src`    | array of globs | no       | Test files. Default: `["tests/**/*.jo"]`. |
| `target` | string         | no       | Backend for tests. Resolved in order: explicit `[test].target` → `[main].target` → inherited from `main.ffi` → inferred from FFI deps in `[test.dependencies]` → `"python"`. Values: `"python"`, `"ruby"`. |

## `[main.dependencies]` and `[test.dependencies]`

```toml
[main.dependencies]
# Registry package — check library (default)
agent-api = "^1.0"

# Registry package — link library (hidden from user code; resolves defer defs)
agent-runtime-python = { version = "^1.0", link = true }

# Local path — uses jo.toml in that directory
agent-api = { path = "../agent-api" }

# Local path — explicit spec
agent-api = { path = "../agent-api", spec = "api.toml" }

# Same directory — different spec
agent-api = { path = ".", spec = "api.toml" }
```

`[test.dependencies]` are used only during `jo test` and not included in release artifacts.

## `[main.links]` and `[test.links]`

Explicit wiring of `defer def`s. All entries are required — unresolved `defer def`s are a build error.

```toml
[main.links]
"agentapi.runTask"   = "usertask.runTask"
"agentapi.onStartup" = "usertask.onStartup"

[test.links]
"agentapi.runTask" = "mocks.fakeRunTask"   # override for tests
```

`[test.links]` is merged with `[main.links]`. Test overrides take precedence.

## `[mirrors]`

Project-level artifact mirrors shared by the team and CI.

```toml
[mirrors]
urls = ["https://mirror.mycompany.com/jo-packages"]
```

Precedence: `[mirrors]` in build spec → `[mirrors]` in `~/.jo/config.toml` → canonical source URL.

