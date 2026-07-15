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

`jo.toml`:

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
target = "python"
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

Add `mustache` to the app module:

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
target = "python"
dependencies = [
  { package = "mustache", version = "1.0" },
]
```

Use it in `src/main.jo`. Give the module a namespace so other modules can import it:

```jo
namespace Hello

import Template

def greet(name: String): String =
  val tmpl = Template.parse("Hello, {{name}}!")
  tmpl.render({ "name": name })

def main() =
  println: greet "world"
```

Run it:

```sh
jo run
```

```
Hello, world!
```

`jo run` fetches packages automatically. If `jo.lock` is missing, Jo creates it. After that, run `jo lock` when you change package dependencies.

## Testing

Tests are ordinary app modules. Add a `test` module that depends on the `app` module it tests:

```toml
jo = "1.0"
default = "app"

[module.app]
kind = "app"
src = ["src/"]
target = "python"
dependencies = [
  { package = "mustache", version = "1.0" },
]

[module.test]
kind = "app"
src = ["tests/"]
target = "python"
dependencies = [
  { module = "app" },
  { package = "jo-test", version = "0.1" },
]
```

`{ module = "app" }` gives the tests access to everything in `src/`. Both modules are apps, and `jo run test` runs the test's `main` — an app module's own `main` is only used when you build that module.

Write a test in `tests/Main.jo`. It imports the app's namespace and tests the app's own function:

```jo
import Test.*
import Hello

def main() =
  run do () =>
    suiteGreet()

def suiteGreet() =
  suite "greet" do
    test "renders the name" do
      assertEqual (Hello.greet "world") "Hello, world!"
    end
  end
```

Run the tests:

```sh
jo run test
```

```
greet
  ✓ renders the name
1 passed
```

See [Testing](guides/testing.md) for test dependencies, link dependencies, and depth settings.

## Next Steps

- [Concepts](concepts/projects.md) — understand projects and modules
- [Managing Dependencies](guides/dependencies.md) — add packages and source modules
- [Testing](guides/testing.md) — define and run test modules
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
