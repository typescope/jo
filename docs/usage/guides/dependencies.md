# Managing Dependencies

## Registry Packages

Add registry packages to a module's `dependencies` array:

```toml
[module.app]
kind = "app"
target = "python"
dependencies = [
  { package = "mustache", version = "1.0" },
]
```

## Source Module Dependencies

Same-project module dependencies do not use a version constraint:

```toml
dependencies = [
  { module = "api" },
]
```

External project module dependencies use `path` plus a module id:

```toml
dependencies = [
  { path = "../agent-api", module = "api" },
]
```

An external source project is part of your build, not a boundary around it. Jo reads its `jo.toml` to find the module's sources and dependencies. It then resolves those together with yours. Its registry packages land in *your* `jo.lock`. Everything builds with one compiler.

Its own `jo.lock` is ignored while you consume it as source. That lock is for building that project on its own. If you and it need incompatible versions of the same package, `jo lock` reports a conflict. This is the same conflict you would get from two modules in your own spec.

## Link Dependencies

Add `link = true` for a dependency that resolves `defer def`s at link time but is hidden from user code:

```toml
dependencies = [
  { package = "agent-runtime-python", version = "1.0", link = true },
]
```

Link dependencies are ignored when generating package metadata.

## Test Dependencies

Tests are app modules:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "api" },
  { package = "jo-test", version = "0.1" },
]
```

Run them with:

```sh
jo run test
```

See [Testing](testing.md) for a complete test module setup.

## The Lock File

`jo lock` resolves all modules in the project and writes one lock file next to the spec. `jo build`, `jo check`, `jo run`, and `jo doc` also resolve all modules when they need to create a missing lock file. If it exists, they use it as-is.

Commit `jo.lock` to source control.

## Updating Dependencies

Resolve dependencies and rewrite the lock file explicitly:

```sh
jo lock
```

## Pinning A Package Exactly

Most projects should rely on Jo's normal compatibility-line resolution. If the root
project needs an explicit override, add `[pinning]` to `jo.toml`:

```toml
[pinning]
mustache = "1.2.3"
```

This is a hard exact requirement for the root project. If it conflicts with the
dependency graph, Jo fails with an explicit pinned-version error.

## Viewing Dependencies

```sh
jo deps       # show the default module dependency tree
jo deps app   # show one module dependency tree
```
