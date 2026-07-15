# Testing

Tests are ordinary app modules. Put reusable code in a lib module, define a test app module, and run it with `jo run <module>`.

## Test Module

```toml
jo = "1.0"
default = "app"

[module.lib]
kind = "lib"
src = ["src/"]

[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "lib" },
  { package = "jo-test", version = "0.1" },
]
```

The test module has its own dependency list. Dependencies are not inherited from another module unless they are declared.

A test module is an app module, so it declares its own `target`. In a Ruby project, write `target = "ruby"` on the test module too — nothing is inherited from the module under test.

## Running Tests

```sh
jo run test
jo check test
```

`jo run test` builds and runs the `test` app module. `jo check test` type-checks the same module closure without producing a runnable target.

## Link Dependencies

Test modules may declare link dependencies when they need test-specific implementations for deferred functions:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
dependencies = [
  { module = "lib" },
  { package = "agent-runtime-test", version = "1.0", link = true },
]
```

Link dependencies are used only at link time and are not written to package metadata.

## Dependency Depth

Tests are app modules, so normal app defaults apply. Set `depth` on the test module if tests need more package depth than production modules:

```toml
[module.test]
kind = "app"
target = "python"
src = ["tests/"]
depth = 2
dependencies = [
  { module = "lib" },
  { package = "jo-test", version = "0.1" },
]
```
