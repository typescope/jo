# Build Spec Reference

The build spec is a TOML file (`jo.toml` by default) that describes how to build a project. Pass `--spec <file.toml>` to use a different file.

## Top-Level Fields

| Field  | Type   | Required | Description |
|--------|--------|----------|-------------|
| `jo`   | string | yes      | Compiler compatibility line, e.g. `"1.0"`. Uses `MAJOR.MINOR` format. `1.0` means “any compatible `1.x.y` compiler version, at least `1.0.0`”. |
| `name`  | string  | yes      | Project name. Must start with a letter; may contain letters, digits, and hyphens (e.g. `"my-app"`, `"http2-client"`). Used as the build output directory name and, for lib builds, the package identifier. |
| `depth` | integer | no       | Default maximum package-dependency tree height for this project. `[main].depth` and `[test].depth` may override it per module. If no module override is present, the effective default is `0` for libraries and `1` for apps. Local `path` projects do not count toward this value. |

## `[pinning]` — Root-Only Exact Overrides

`[pinning]` lets the root build spec force an exact package version when normal
compatibility-line resolution needs a manual override.

```toml
[pinning]
mustache = "1.2.3"
```

Rules:

- values use `MAJOR.MINOR.PATCH`
- only the root build spec may contain `[pinning]`
- `meta.toml` never contains pinning
- published packages never export pins transitively
- a pin is a hard requirement: if it conflicts with dependency constraints, Jo fails explicitly

This is mainly intended for apps and top-level builds. Libraries should continue to
declare normal compatibility constraints only.

## `[package]` — Library Build Options

Presence of this section marks the build as a **library**. Publishing metadata fields are described in [Library Metadata](library-metadata.md). The following fields affect the build:

| Field        | Type    | Required | Description |
|--------------|---------|----------|-------------|
| `version`    | string  | yes      | Package version in `MAJOR.MINOR.PATCH` format, e.g. `"1.2.3"`. |
| `runtime`    | string  | no       | Optional assertion: `"pure"`, `"python"`, `"ruby"`. For library builds, `jo build` uses it to derive the matching `--use-runtime-api` flag. For app builds, it constrains target selection but does not inject `--use-runtime-api`. `jo package` verifies it against the computed value. |

## `[main]` — Main Source

| Field             | Type             | Required | Description |
|-------------------|------------------|----------|-------------|
| `src`             | array of globs   | no       | Source files. Default: `["src/**/*.jo"]`. |
| `target`          | string           | no       | Backend: `"python"`, `"ruby"`. Default: `"python"`. |
| `depth`           | integer          | no       | Maximum allowed package-dependency tree height for the main module. Overrides the top-level `depth`. If absent, `main` inherits the project-level `depth`, or defaults to `0` for libraries and `1` for apps. Local `path` projects do not count toward this value. See [Dependency Resolution](dependency-resolution.md). |
| `compile-options` | array of strings | no       | Extra flags passed verbatim to `jo compile` when building this module. For example, `["--no-stdlib"]` is used when building the standard library itself. This can still be used to request a specific runtime API manually when needed. |

## `[test]` — Test Source

| Field    | Type           | Required | Description |
|----------|----------------|----------|-------------|
| `src`    | array of globs | no       | Test files. Default: `["tests/**/*.jo"]`. |
| `target` | string         | no       | Backend for tests. Resolved in order: explicit `[test].target` → `[main].target` → inherited from `package.runtime` → inferred from runtime-constrained deps in `[test.dependencies]` → `"python"`. Values: `"python"`, `"ruby"`. |
| `depth`  | integer        | no       | Maximum allowed package-dependency tree height for the test module. Overrides the top-level `depth`. If absent, `test` inherits the project-level `depth`; if the project also omits `depth`, `test` inherits `main`'s effective depth. Local `path` projects do not count toward this value. |

## `[main.dependencies]` and `[test.dependencies]`

```toml
[main.dependencies]
# Registry package — check library (default)
agent-api = "1.0"

# Registry package — link library (hidden from user code; resolves defer defs)
agent-runtime-python = { version = "1.0", link = true }

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
