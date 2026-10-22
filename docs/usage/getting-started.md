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

`jo.toml` (for Jo 0.14.x):

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

Use [jtest](https://github.com/typescope/jtest) to test the app. Tests are ordinary app modules, so add a `test` module that depends on the `app` module and the published `jtest-python` package:

```toml
jo = "0.14"
default = "app"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"

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

`jo run test` fetches packages automatically if `jo.lock` is missing and creates it. Since `jo run` already created a lock file for the app, run `jo lock` after adding the test dependency:

```sh
jo lock
```

## Testing

Give the app a function to test. Update `src/main.jo` with a namespace so the tests can import it:

```jo
namespace Hello

def greet(name: String): String = "Hello, \{name}!"

def main() =
  println: greet "world"
```

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

Run the tests:

```sh
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
