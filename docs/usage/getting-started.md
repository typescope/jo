# Getting Started

The `jo` command is Jo's project tool. It handles compilation, dependency management, packaging, documentation, and running app modules from a `jo.toml` build spec.

## Hello World

Create a new app:

```sh
jo new hello
cd hello
```

This creates:

```
hello/
  jo.toml
  src/
    main.jo
```

`jo.toml` (for Jo 0.14.7):

```toml
jo = "0.14"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"
```

Edit `src/main.jo`:

```jo
def main() =
  println("Hello, world!")
```

Run it:

```sh
jo run
```

```
Hello, world!
```

## Adding a Dependency

Add [ansi](https://github.com/liufengyun/ansi), a pure Jo library for terminal colors and styles, to the app module:

```toml
jo = "0.14"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"
packages = [{ name = "ansi", version = "0.1" }]
```

Use it in `src/main.jo`. Give the app a namespace and a function that the tests can import later:

```jo
namespace Hello

import ansi

def greet(name: String): String = "Hello, \{name}!"

def main() =
  println: ansi.green(greet("world"))
```

Run `jo lock` after changing package dependencies, then run the app:

```sh
jo lock
jo run
```

The terminal displays `Hello, world!` in green. `ansi.green` returns a string containing ANSI escape codes; it does not print anything itself. Use `ansi.strip` to remove the codes when you need plain text.

If `jo.lock` is missing, `jo run` fetches packages automatically and creates it. Once the lock file exists, run `jo lock` whenever you change dependencies.

## Testing

Use [jtest](https://github.com/typescope/jtest) to test the app. Tests are ordinary app modules, so add a `test` module that depends on the `app` module and the published `jtest-python` package:

```toml
jo = "0.14"
default = "app"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"
packages = [{ name = "ansi", version = "0.1" }]

[module.test]
kind = "app"
src = ["tests/"]
platform = "python"
enable-ffi = true
depth = 2
modules = ["app"]
packages = [{ name = "jtest-python", version = "0.2" }]
```

`modules = ["app"]` gives the tests access to everything in `src/`. Both modules are apps, and `jo run test` runs the test's `main` — an app module's own `main` is only used when you build that module.

`jtest-python` provides the Python runner and depends on the shared `jtest` package, so the test module needs `depth = 2`. The runner also requires `enable-ffi = true`.

Write a test in `tests/Main.jo`. It imports the app's namespace and tests the app's own function:

```jo
import jo.IO.stdout
import jo.IO.args
import jtest.*
import Hello

def main(): Unit receives stdout, args =
  val root = Testing.define("hello tests", () => suiteGreet())
  Runner.runWithArgs(root)

private def suiteGreet(): Unit =
  suite: "greet", () =>
    test: "renders the name", () =>
      checkEquals("greeting", Hello.greet("world"), "Hello, world!")
```

Refresh the lock file for the new dependency and run the tests:

```sh
jo lock
jo run test
```

```
hello tests:
  greet
    ok   renders the name

1 passed, 0 failed in 0.0s
```

See [Testing](guides/testing.md) for test dependencies, link dependencies, and depth settings.

## Next Steps

- [Concepts](concepts/projects.md) — understand projects and modules
- [Managing Dependencies](guides/dependencies.md) — add packages and source modules
- [Testing](guides/testing.md) — define and run test modules
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
