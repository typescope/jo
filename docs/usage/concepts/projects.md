# Projects

A Jo project is a directory containing a build spec (`jo.toml`) and source files. The build spec declares the compiler version required, dependencies, and — for libraries — publishing metadata.

## App vs Library

Build kind is implicit:

- **Library** — `[package]` section present. Produces a `.joy` package for distribution.
- **App** — `[package]` section absent. Produces an executable or script.

**Minimal library** (`agent-api.toml`):

```toml
jo      = ">=1.0"
name    = "agent-api"

[package]
version = "1.0.0"
license = "MIT"
```

**Minimal app** (`jo.toml`):

```toml
jo = ">=1.0"

[main]
target = "python"
```

## Build Output Directory

The build output directory is named after the `name` field in the spec:

| Spec `name` field | Output directory       |
|-------------------|------------------------|
| `hello`           | `.build/hello/`        |
| `my-agent`        | `.build/my-agent/`     |
| `agent-api`       | `.build/agent-api/`    |

This allows multiple projects to coexist in the same directory, each with its own spec file, output directory, and lock file.

## Build Output Layout

```
.build/<name>/
  jo-<version>/
    sast/      # compiled .sast files — always produced
    target/    # executable or script — app builds only
  release/     # publishable .joy artifact — jo package only
  doc/         # generated API docs
```

Compiler outputs (`sast/`, `target/`) are nested under a `jo-<version>/` subdirectory so that switching compiler versions does not mix artifacts. `sast/` is always produced — even for apps — because `jo test` compiles the main source as a library before building the test suite.

## Project Layout

```
my-project/
  jo.toml          # build spec (tracked)
  jo.lock          # lock file (tracked)
  src/             # main source files (default: src/**/*.jo)
  tests/           # test source files (default: tests/**/*.jo)
  docs/
  .build/          # build artifacts (gitignored)
```

## Multiple Projects in One Directory

Multiple specs in the same directory reference each other via `path = "."`:

```
my-project/
  api.toml
  runtime.toml
  app.toml
  src/
    API.jo
    Runtime.jo
    App.jo
```

```toml
# app.toml
jo = ">=1.0"

[main]
target = "python"

[main.dependencies]
agent-api      = { path = ".", spec = "api.toml" }
agent-runtime  = { path = ".", spec = "runtime.toml", link = true }
```
