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

Use it in `src/main.jo`:

```jo
import Template

def main() =
  val tmpl = Template.parse("Hello, {{name}}!")
  println tmpl.render({ "name": "world" })
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

Tests are ordinary app modules. Add a `test` module with its own dependencies:

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
  { package = "mustache", version = "1.0" },
  { package = "jo-test", version = "0.1" },
]
```

Write a test in `tests/Main.jo`:

```jo
import Test.*
import Template

def main() =
  run do () =>
    suiteTemplate()

def suiteTemplate() =
  suite "template" do
    test "render" do
      val tmpl = Template.parse("Hello, {{name}}!")
      assertEqual tmpl.render({ "name": "world" }) "Hello, world!"
    end
  end
```

Run the tests:

```sh
jo run test
```

```
template
  ✓ render
1 passed
```

See [Testing](guides/testing.md) for test dependencies, link dependencies, and depth settings.

## Next Steps

- [Concepts](concepts/projects.md) — understand projects and modules
- [Managing Dependencies](guides/dependencies.md) — add packages and source modules
- [Testing](guides/testing.md) — define and run test modules
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
