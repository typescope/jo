# Testing

Tests are ordinary app modules. A test module depends on the module it tests, and you run it with `jo run <module>`.

## Testing An App

Depend on the app module directly:

```toml
jo = "1.0"
default = "app"

[module.app]
kind = "app"
target = "python"
src = ["src/"]

[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "app" },
  { package = "jo-test", version = "0.1" },
]
```

The test module now sees everything the app defines. You do not need to split the app into a library to test it.

An app module is a library plus a target, link wiring, and an entry point. Depending on one gives you the code and the wiring, not the entry point. Jo takes the entry point from the sources of the module it is building, so `jo run test` runs the test's `main`. The app's `main` comes in as an ordinary function and is never mistaken for the entry point.

A test module is an app module, so it declares its own `target`, and it must match the target of the app module it depends on.

## Testing A Library

Depend on the lib module. This is the same shape:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "lib" },
  { package = "jo-test", version = "0.1" },
]
```

The test module has its own dependency list. Package dependencies are not inherited from the module under test — declare what the tests themselves import.

## Running Tests

```sh
jo run test
jo check test
```

`jo run test` builds and runs the `test` app module. `jo check test` type-checks the same module closure without producing a runnable target.

## Overriding Links

Depending on an app module copies its links. Declare a link with the same `from` to override one for the tests:

```toml
[module.app]
kind = "app"
target = "python"
links = [
  { from = "agentapi.runTask", to = "app.runTask" },
]

[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "app" },
  { package = "jo-test", version = "0.1" },
]
links = [
  { from = "agentapi.runTask", to = "mocks.fakeRunTask" },
]
```

The test inherits the app's wiring and replaces one entry. Links you do not mention are inherited unchanged.

If the app declares an explicit entry point with `{ from = "jo.main", to = "..." }`, the test inherits that too and must override it with its own `main`. Otherwise `jo run test` runs the app.

## Test-Specific Link Libraries

Link libraries are inherited from an app module dependency and cannot be overridden. If your tests need a different link library than the app uses — a test runtime in place of the real one — do not depend on the app module. Put the shared code in a lib module and depend on that:

```toml
[module.core]
kind = "lib"
src = ["src/"]

[module.app]
kind = "app"
target = "python"
src = ["app/"]
dependencies = [
  { module = "core" },
  { package = "agent-runtime-python", version = "1.0", link = true },
]

[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "core" },
  { package = "agent-runtime-test", version = "1.0", link = true },
]
```

Lib modules carry no links and no link libraries, so depending on one inherits nothing. Each app module states its own runtime.

Link dependencies are used only at link time and are not written to package metadata.

## Dependency Depth

Tests are app modules, so normal app defaults apply. Set `depth` on the test module if tests need more package depth than the code they test:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
depth = 2
dependencies = [
  { module = "app" },
  { package = "jo-test", version = "0.1" },
]
```
