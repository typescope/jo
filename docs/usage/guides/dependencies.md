# Managing Dependencies

A module declares two kinds of dependency in two arrays. `modules` are built from source. `packages` are resolved from the registry.

```toml
[module.app]
kind = "app"
platform = "python"
src = ["src/"]
modules = ["api"]
packages = [{ name = "ansi", version = "0.1" }]
```

## Registry Packages

Every package needs a name and a compatibility line:

```toml
packages = [{ name = "ansi", version = "0.1" }]
```

[ansi](https://github.com/liufengyun/ansi) is a published, dependency-free library for terminal styles. Import its namespace in your app:

```jo
import ansi

def main() =
  println: ansi.bold("Hello, world!")
```

Run `jo lock` after adding the dependency, then `jo run app`.

## Source Module Dependencies

A module in the same project is just its id:

```toml
modules = ["api", "core"]
```

Source modules never carry a version. They are built from source, so there is nothing to resolve.

A module from another project adds a `path`:

```toml
modules = [
  "api",
  { id = "helpers", path = "../agent-api" },
]
```

An external source project is part of your build, not a boundary around it. Jo reads its `jo.toml` to find the module's sources and dependencies. It then resolves those together with yours. Its registry packages land in *your* `jo.lock`. Everything builds with one compiler.

Its own `jo.lock` is ignored while you consume it as source. That lock is for building that project on its own. If you and it need incompatible versions of the same package, `jo lock` reports a conflict. This is the same conflict you would get from two modules in your own spec.

## Link Dependencies

Add `link = true` for a dependency that resolves `defer def`s at link time but is hidden from user code. For a local `runtime` module:

```toml
modules = [{ id = "runtime", link = true }]
```

The same flag is available on registry package dependencies when you use a published implementation of your API. Link dependencies are ignored when generating package metadata.

## Test Dependencies

Tests are app modules:

```toml
[module.test]
kind = "app"
platform = "python"
enable-ffi = true
src = ["tests/"]
depth = 2
modules = ["api"]
packages = [{ name = "jtest-python", version = "0.2" }]
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
ansi = "0.1.1"
```

This is a hard exact requirement for the root project. If it conflicts with the
dependency graph, Jo fails with an explicit pinned-version error.

## Viewing Dependencies

```sh
jo deps       # show the default module dependency tree
jo deps app   # show one module dependency tree
```
