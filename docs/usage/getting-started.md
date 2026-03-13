# Getting Started

The `jo` command is Jo's unified build tool. It handles compilation, testing, dependency management, and publishing — all from a single `jo.toml` build spec.

## Hello World

Create a new app:

```sh
jo new hello
cd hello
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

## Hello Library

Create a library and a simple test:

```sh
jo new greet --lib
cd greet
```

Edit `src/Greet.jo`:

```jo
namespace Greet

def hello(name: String): String =
  "Hello, " + name + "!"
```

Edit `tests/Main.jo`:

```jo
import Greet

def main() =
  println(Greet.hello("world"))
```

Run tests:

```sh
jo test
```

```
Hello, world!
```

## Next Steps

- [Concepts](concepts/projects.md) — understand how projects and packages work
- [Managing Dependencies](guides/dependencies.md) — add packages from the registry
- [Build Spec Reference](reference/build-spec.md) — all `jo.toml` fields
