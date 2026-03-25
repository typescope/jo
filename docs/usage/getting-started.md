# Getting Started

The `jo` command is Jo's unified build tool. It handles compilation, testing, dependency management, and publishing — all from a single `jo.toml` build spec.

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
jo   = ">=1.0"
name = "hello"

[main]
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

Add `mustache` to `jo.toml`:

```toml
jo   = ">=1.0"
name = "hello"

[main]
target = "python"

[main.dependencies]
mustache = "^1.0"
```

Use it in `src/main.jo`:

```jo
import Template

def main() =
  val tmpl = Template.parse("Hello, {{name}}!")
  println tmpl.render({ "name": "world"})
```

Run it:

```sh
jo run
```

```
Hello, world!
```

`jo run` fetches `mustache` automatically and writes a `jo.lock` to pin the resolved version. Later runs use that lock strictly until you refresh it with `jo lock`.

## Testing

Add a test framework to `jo.toml`:

```toml
jo   = ">=1.0"
name = "hello"

[main]
target = "python"

[main.dependencies]
mustache = "^1.0"

[test.dependencies]
jo-test = "^0.1"
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
      assertEqual tmpl.render({"name": "world"}) "Hello, world!"
    end
  end
```

Run the tests:

```sh
jo test
```

```
template
  ✓ render
1 passed
```

`jo test` fetches `jo-test` automatically and writes a `jo.lock` to pin the resolved version. Later test runs use that lock strictly until you refresh it with `jo lock`.

## Next Steps

- [Concepts](concepts/projects.md) — understand how projects and packages work
- [Managing Dependencies](guides/dependencies.md) — add packages from the registry
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
