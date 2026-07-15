# jo new

Create a new project with a standard scaffold.

## Usage

```
jo new <name> [--lib]
```

## Options

| Option  | Description                             |
|---------|-----------------------------------------|
| `--lib` | Create a library project. Default: app. |

## Output

**App** (`my-agent/jo.toml`):

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
target = "python"
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
```
