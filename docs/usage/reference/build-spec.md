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

Module ids are ASCII resource owners. They must start with an ASCII letter and
may contain only ASCII letters, digits, and hyphens.

| Field             | Type             | Required | Description |
|-------------------|------------------|----------|-------------|
| `kind`            | string           | yes      | `"lib"` or `"app"`. |
| `src`             | array of paths   | yes      | Source directories or `.jo` files for this module. Directories are searched recursively. |
| `resources`       | array of mappings | no       | Resource source/destination mappings for this module. The short form `source` means `source:source`. Default: `[]`. |
| `platform`        | string           | app only | Platform this module is bound to. Apps: `"python"` or `"ruby"`, required. Libs: `"pure"`, `"python"`, or `"ruby"`, default `"pure"`. |
| `enable-ffi`      | boolean          | no       | May this module's own code call the platform's FFI API (`py.*`, `rb.*`). Default: `false`. |
| `depth`           | integer          | no       | Maximum registry package dependency depth for this module. Default: `0` for `lib`, `1` for `app`. |
| `compile-options` | array of strings | no       | Extra flags passed verbatim to `jo compile` for this module. |
| `modules`         | array            | no       | Source module dependencies. Strings, or tables with `id`. |
| `packages`        | array of tables  | no       | Registry package dependencies. |
| `links`           | array of tables  | app only | Explicit `defer def` wiring. Invalid on lib modules. |

### `platform`

`platform` answers one question for both kinds of module: which platform is this module bound to. For an app that means the backend it emits. For a lib, which has no backend, it means the platform its compiled output requires.

Every app module states its own `platform`. There is no default and no inheritance from another module, so a test module in a Ruby project declares `platform = "ruby"` like any other app module.

Lib modules default to `platform = "pure"`, which means the code is runtime-independent and can be reused from apps on any platform. A lib is non-pure either because it calls an FFI API itself, or because it depends on a module that does.

A module's `platform` is recorded in generated package metadata. See [Library Metadata](library-metadata.md).

### `enable-ffi`

`enable-ffi` answers a separate question: may *this module's own source* call `py.*` or `rb.*`. It is `false` by default, so naming a platform does not by itself hand a module the FFI API:

```toml
[module.app]
kind = "app"
platform = "python"     # emits Python. py.* is NOT in scope here
```

Set it to `true` to open the FFI surface:

```toml
[module.pyffi]
kind = "lib"
platform = "python"
enable-ffi = true       # this module's code may call py.*
```

Reaching a runtime API is a capability, so it is granted explicitly rather than inherited from a platform choice. Most modules on a platform never touch `py.*` — they call libraries that do — and the default keeps that surface closed for them. A lib bound to a platform only because a *dependency* is bound to it needs no `enable-ffi` at all.

`enable-ffi = true` with `platform = "pure"` is an error — there is no FFI API to enable.

Example:

```toml
jo = "1.0"
default = "app"

[module.api]
kind = "lib"
src = ["api/src"]

[module.app]
kind = "app"
src = ["app/src", "generated/AppConfig.jo"]
platform = "python"
modules = ["api"]
packages = [{ name = "mustache", version = "1.0" }]
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]
```

`src` entries are project-relative paths. A directory entry includes every `.jo`
file under that directory recursively. A file entry includes that file only and
must end with `.jo`. Glob syntax is not supported.

### `resources`

`resources` declares data files owned by a module:

```toml
[module.ui]
kind = "lib"
src = ["src/"]
resources = ["assets/", "config/default.json"]
```

Each entry is a resource mapping. The short form `source` means
`source:source`; use `source:destination` when the runtime resource path should
not match the source tree layout:

```toml
resources = [
  "assets/",                            # assets/:assets/
  "web/:public/",                       # directory mapped to public/
  "config/default.json:defaults.json",  # file mapped to defaults.json
]
```

The source side is always resolved relative to the project root. The destination
side is a logical path below the module's runtime resource owner directory:

- a source file maps to exactly the destination file path
- a source directory maps every regular file below it into the destination
  directory, preserving paths relative to the source directory
- a trailing `/` is accepted on either side and does not change the destination
  semantics

The example above packages:

```text
resources/assets/index.html
resources/assets/app.js
resources/public/index.html
resources/public/app.js
resources/defaults.json
```

There is no overlay behavior. If two entries produce the same destination, the
build fails:

```toml
resources = ["assets/", "assets/index.html"] # duplicate destination
```

Resource declaration paths are portable logical paths, not host OS path strings.
Rules:

- entries contain either zero or one `:` separator
- `:` is reserved for `source:destination` mappings and cannot appear inside a
  path
- both `source` and `destination` must be non-empty when `:` is used
- source and destination paths are relative
- `/` is the only separator
- a trailing `/` is allowed
- absolute paths are rejected, including `/tmp/x` and `C:/x`
- `..` segments are rejected
- `.` segments are rejected
- interior empty segments such as `assets//logo.svg` are rejected
- backslash is rejected, not normalized as a Windows separator
- glob syntax is not supported
- symlinks are rejected
- resource files may have any extension

Source and destination syntax is validated when Jo reads `jo.toml`, so invalid
declarations fail even in lib-only workflows. Filesystem checks, symlink checks,
and duplicate expanded targets are validated when a command collects resources,
such as `jo package` or an app build.

Packaging a module copies its own resources into the `.joy` archive under
`resources/` using the destination side of each mapping. The companion source
archive includes the original source-side resource files, so mapped resources
can still be audited from the source layout. Plain lib compilation does not copy
resources.

App compilation copies resources beside the generated app output:

```text
.build/app/jo-1.0/target/app.py
.build/app/jo-1.0/target/resources/<owner>/<resource-path>
```

Only app builds copy resources recursively from the app module and its selected
source/package dependency closure.

The `<owner>` directory is derived as follows:

- registry package dependency: package name
- source module with `[module.<id>.package]`: package name
- source module without package metadata: module id

Owner names use the same ASCII grammar as module ids and package names: they
start with an ASCII letter and contain only ASCII letters, digits, or hyphens.

If two modules or packages in the selected app closure derive the same owner and
both declare resources, the app build fails with a duplicate resource owner
error. Use package metadata when an external source module needs a stable owner
that will not collide with another module id.

Owner directories keep copied files separate and deterministic. Runtime
`Resources` values are normally scoped to one owner, for example
`new py.resource.ResourceBundle("my-web")`, and modules declare their own
resource context parameters. Resources are still bundled application assets, not
secrets: code with FFI access can instantiate additional owner-scoped bundles.

See [Resources](../guides/resources.md) for runtime access patterns.

## Dependencies

Source modules and registry packages are separate arrays.

### `modules`

A bare string is a module in this project:

```toml
modules = ["api", "core"]
```

That is shorthand for `{ id = "api" }`. Use the table form when an entry needs more:

```toml
modules = [
  "api",
  { id = "helpers", path = "../testing" },
  { id = "runtime", link = true },
]
```

| Field  | Required | Description |
|--------|----------|-------------|
| `id`   | yes      | Module id. Never carries a version — source modules are built from source. |
| `path` | no       | Directory containing the `jo.toml` that defines the module. Omit for this project. |
| `link` | no       | Link-only dependency. Default `false`. |

`path` makes the module an external source dependency. Jo loads `path/jo.toml` for that module's sources and dependencies and resolves them together with this project's own. The external project's packages are locked in this project's `jo.lock`, and its own `jo.lock` is ignored. See [Dependency Resolution](dependency-resolution.md).

### `packages`

```toml
packages = [
  { name = "mustache", version = "1.0" },
  { name = "agent-runtime-python", version = "1.0", link = true },
]
```

| Field     | Required | Description |
|-----------|----------|-------------|
| `name`    | yes      | Registry package name. Starts with an ASCII letter and contains only ASCII letters, digits, or hyphens. |
| `version` | yes      | Compatibility line, e.g. `"1.0"`. |
| `link`    | no       | Link-only dependency. Default `false`. |

There is no short form for packages, because a package dependency always needs both a name and a version. A module dependency needs only an id, which is why it has one.

### Link dependencies

`link = true` works on either array. Link dependencies are hidden from user code and are omitted from generated package metadata. Like `links`, they are valid only on app modules — a link library resolves deferred definitions when an executable is produced, and only app modules produce one.

Hidden from user code does not mean hidden from the runnable app artifact. App
builds still copy resources from link-only dependencies in the selected closure,
because linked runtime code may need its own bundled assets. Hidden resources
can still be read by linked code when the app binds an owner-scoped resource
capability for that code.

## Links

Explicit wiring of `defer def`s. Valid only on app modules:

```toml
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]
```

Duplicate `from` symbols within one module's `links` are an error.

### Depending On An App Module

An app module is a lib module plus a platform, link wiring, and an entry point. A source dependency on one copies its links and its link libraries into the depending module.

Copied links are **overridable**. Declare a link with the same `from` and the local one wins:

```toml
[module.app]
kind = "app"
platform = "python"
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]

[module.test]
kind = "app"
platform = "python"
modules = ["app"]
links = [
  { from = "agentapi.runTask", to = "mocks.fakeRunTask" },
]
```

Copied link libraries are **not** overridable. They are inherited as-is. A module that needs different link libraries should depend on the lib module holding the shared code instead, and declare its own.

Other rules:

- copying is transitive through a chain of app modules
- a dependent app module must declare the same `platform` as the app module it depends on
- two copied links with the same `from` and different `to` are an error, unless the dependent declares its own link for that `from`
- depending on a lib module copies nothing, since lib modules have no links

`jo.main` is an ordinary link. Most apps never declare it — Jo finds a conforming `main` in the module's own sources. A `main` arriving through a dependency's compiled output is never a candidate, which is what lets an app module serve as a library. But if an app *does* declare `{ from = "jo.main", to = "..." }`, a dependent app module inherits it and must override it to run its own entry point.

## `[module.<id>.package]`

Any module can be publishable by adding a nested package section.

| Field         | Type            | Required | Description |
|---------------|-----------------|----------|-------------|
| `name`        | string          | yes      | Package name. Starts with an ASCII letter and contains only ASCII letters, digits, or hyphens. |
| `version`     | string          | yes      | Package version in `MAJOR.MINOR.PATCH` format. |
| `description` | string          | no       | Package description. |
| `authors`     | array of string | no       | Package authors. |
| `homepage`    | string          | no       | Homepage URL. |
| `license`     | string          | no       | License identifier. |
| `keywords`    | array of string | no       | Search keywords. |

```toml
[module.api]
kind = "lib"
platform = "pure"

[module.api.package]
name = "agent-api"
version = "1.2.0"
```

The package table has no `platform` of its own. Generated `meta.toml` takes it from the module, so there is one place to state it and it cannot drift from what was compiled.

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
