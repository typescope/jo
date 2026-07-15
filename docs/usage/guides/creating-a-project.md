# Creating a Project

## New App

```sh
jo new my-agent
cd my-agent
jo run
```

`jo.toml`:

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
target = "python"
```

## New Library

```sh
jo new my-lib --lib
cd my-lib
jo build
```

`jo.toml`:

```toml
jo = "1.0"

[module.lib]
kind = "lib"
src = ["src/"]

[module.lib.package]
name = "my-lib"
version = "0.1.0"
```

## Tests

Tests are app modules:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "lib" },
]
```

Run them with:

```sh
jo run test
```

See [Testing](testing.md) for test dependencies, link dependencies, and depth settings.

## Multiple Modules

Use one `jo.toml` with multiple `[module.<id>]` sections:

```toml
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
