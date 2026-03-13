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
jo   = ">=1.0.0"
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

Add a dependency to `jo.toml`:

```toml
jo = ">=1.0.0"

[main]
target = "python"

[main.dependencies]
jo-http = "^1.0.0"
```

Use it in `src/main.jo`:

```jo
import Http

def main() =
  val response = Http.get("https://example.com")
  println(response.body)
```

Run it:

```sh
jo run
```

`jo build` fetches `jo-http` automatically and writes a `jo.lock` to pin the resolved version.

## Testing

Add a test framework to `jo.toml`:

```toml
jo = ">=1.0.0"

[main]
target = "python"

[test.dependencies]
jo-test = "^0.1.0"
```

Write a test in `tests/Main.jo`:

```jo
import Test

def main() =
  Test.check("greet", () =>
    val msg = "Hello, world!"
    Test.assert(msg == "Hello, world!")
  )
```

Run the tests:

```sh
jo test
```

```
✓ greet
1 passed
```

`jo build` fetches `jo-test` automatically and writes a `jo.lock` to pin the resolved version.

## Next Steps

- [Concepts](concepts/projects.md) — understand how projects and packages work
- [Managing Dependencies](guides/dependencies.md) — add packages from the registry
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
