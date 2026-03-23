# Projects

A Jo project is a directory containing a build spec (`jo.toml`) and source files. The build spec declares the compiler version required, dependencies, and — for libraries — publishing metadata.

## App vs Library

Build kind is implicit:

- **Library** — `[package]` section present. Produces a `.joy` package for distribution.
- **App** — `[package]` section absent. Produces an executable or script.

**Minimal library** (`agent-api.toml`):

```toml
jo = ">=1.0"

[package]
name    = "agent-api"
version = "1.0.0"
license = "MIT"
```

**Minimal app** (`jo.toml`):

```toml
jo = ">=1.0"

[main]
target = "python"
```

## Spec Filename and Build Output

The build output directory is always named after the spec filename stem:

| Spec file         | Output directory       |
|-------------------|------------------------|
| `jo.toml`         | `.build/jo/`           |
| `my-agent.toml`   | `.build/my-agent/`     |
| `agent-api.toml`  | `.build/agent-api/`    |

This allows multiple projects to coexist in the same directory, each with its own spec file, output directory, and lock file.

## Build Output Layout

```
.build/<stem>/
  sast/        # compiled .sast files — always produced
  target/      # executable or script — app builds only
  release/     # publishable .joy artifact — jo build-release only
  pip.txt      # merged Python deps
  gems.txt     # merged Ruby deps
```

`sast/` is always produced — even for apps — because `jo test` compiles the main source as a library before building the test suite.

## Project Layout

```
my-project/
  jo.toml          # build spec (tracked)
  jo.lock          # lock file: tracked for apps, gitignored for libs
  pip.txt          # direct Python foreign deps (optional)
  gems.txt         # direct Ruby foreign deps (optional)
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
