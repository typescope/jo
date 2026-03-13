# jo new

Create a new project with a standard scaffold.

## Usage

```
jo new <name> [--lib | --app]
```

## Options

| Option  | Description                              |
|---------|------------------------------------------|
| `--lib` | Create a library project (default: app)  |
| `--app` | Create an application project (explicit) |

## Examples

Create an app project:

```sh
jo new my-agent
```

Create a library project:

```sh
jo new my-lib --lib
```

## Output

**App** (`my-agent/jo.toml`):

```toml
jo = ">=1.0.0"

[main]
target = "python"
```

**Library** (`my-lib/jo.toml`):

```toml
jo = ">=1.0.0"

[package]
name    = "my-lib"
version = "0.1.0"
```
