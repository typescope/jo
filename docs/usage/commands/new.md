# jo new

Create a new project with a standard scaffold.

## Usage

```
jo new [--lib] <name>
```

## Options

| Option  | Description                             |
|---------|-----------------------------------------|
| `--lib` | Create a library project. Default: app. |

## App Scaffold

`jo new my-agent` prints:

```text
Created 'my-agent'

You can now:
  cd my-agent
  jo run
  jo run test
```

It creates:

```text
my-agent/
  jo.toml
  src/
  tests/
```

**App** (`my-agent/jo.toml`):

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"

[module.test]
kind = "app"
src = ["tests/"]
platform = "python"

modules = ["app"]
```

## Library Scaffold

`jo new --lib my-lib` prints:

```text
Created 'my-lib'

You can now:
  cd my-lib
  jo build
  jo run test
```

It creates:

```text
my-lib/
  jo.toml
  src/
  tests/
```

**Library** (`my-lib/jo.toml`):

```toml
jo = "1.0"

[module.lib]
kind = "lib"
src = ["src/"]

[module.lib.package]
name = "my-lib"
version = "0.1.0"

[module.test]
kind = "app"
src = ["tests/"]
platform = "python"

modules = ["lib"]
```
