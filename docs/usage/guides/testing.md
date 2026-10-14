# Testing

Tests are ordinary app modules. A test module depends on the module it tests, and you run it with `jo run <module>`. You can write tests directly or use a testing framework. This guide uses [jtest](https://github.com/typescope/jtest) as one example.

## Testing An App

Suppose your app defines `Calc.add`. Depend on the app module directly and add the jtest runner for its platform. This example uses the Jo and jtest versions in the [jtest README](https://github.com/typescope/jtest#install):

```toml
jo = "0.14"
default = "app"

[module.app]
kind = "app"
platform = "python"
src = ["src/"]

[module.test]
kind = "app"
platform = "python"
enable-ffi = true
src = ["tests/"]
depth = 2
modules = ["app"]
packages = [{ name = "jtest-python", version = "0.2" }]
```

The `packages`, `depth`, and `enable-ffi` settings here are for this jtest example. `jtest-python` brings in the shared `jtest` package, so this dependency path requires `depth = 2`. For Ruby, set `platform = "ruby"` and use `jtest-ruby` at version `0.2` instead. The test source stays the same.

For example, `src/Calc.jo` can contain:

```jo
namespace Calc

def add(a: Int, b: Int): Int = a + b

def main() = println(add(1, 2))
```

Write `tests/Main.jo`:

```jo
import jo.IO.stdout
import jo.IO.args
import jtest.*
import Calc

def main(): Unit receives stdout, args =
  val root = Testing.define("calc tests", () => suites())
  Runner.runWithArgs(root)

private def suites(): Unit =
  suite: "math", () =>
    test: "adds numbers", () =>
      checkEquals("one plus one", Calc.add(1, 1), 2)
```

`Testing.define` collects the suites, `checkEquals` records assertion failures, and `Runner.runWithArgs` reports the results and exits unsuccessfully if a selected test fails.

An app module is a library plus a platform, link wiring, and an entry point. Depending on one gives you the code and the wiring, not the entry point. Jo takes the entry point from the sources of the module it is building, so `jo run test` runs the test's `main`. The app's `main` comes in as an ordinary function and is never mistaken for the entry point.

A test module is an app module, so it declares its own `platform`, and it must match the platform of the app module it depends on.

## Testing A Library

Depend on the lib module. If you use jtest, the setup has the same shape:

```toml
[module.test]
kind = "app"
platform = "python"
enable-ffi = true
src = ["tests/"]
depth = 2
modules = ["lib"]
packages = [{ name = "jtest-python", version = "0.2" }]
```

The test module has its own dependency list. Package dependencies are not inherited from the module under test — declare what the tests themselves import.

## Running Tests

```sh
jo run test
jo check test
jo run test -- math
jo run test -- "math/adds numbers"
```

`jo run test` builds and runs the `test` app module. `jo check test` type-checks the same module closure without producing a runnable target.

With jtest's `Runner.runWithArgs`, the arguments after `--` select tests by path prefix. The root name (`calc tests` above) is not part of the path. A filter that matches no tests exits successfully. See the [jtest README](https://github.com/typescope/jtest#write-tests) for fixtures and parallel suites.

## Overriding Links

Depending on an app module copies its links. Declare a link with the same `from` to override one for the tests:

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
enable-ffi = true
src = ["tests/"]
depth = 2
modules = ["app"]
packages = [{ name = "jtest-python", version = "0.2" }]
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
platform = "python"
src = ["app/"]
modules = ["core"]
packages = [{ name = "agent-runtime-python", version = "1.0", link = true }]

[module.test]
kind = "app"
platform = "python"
enable-ffi = true
src = ["tests/"]
modules = ["core"]
depth = 2
packages = [
  { name = "jtest-python", version = "0.2" },
  { name = "agent-runtime-test", version = "1.0", link = true },
]
```

Lib modules carry no links and no link libraries, so depending on one inherits nothing. Each app module states its own runtime.

Link dependencies are used only at link time and are not written to package metadata.

## Dependency Depth

Tests are app modules, so normal app defaults apply. Set `depth` according to the packages your tests use. In the jtest example above, the path from `test` through `jtest-python` to `jtest` has package depth 2, even though the app itself uses no packages.
