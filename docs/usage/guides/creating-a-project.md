# Creating a Project

## New App

```sh
jo new my-agent
cd my-agent
```

This creates:

```
my-agent/
  jo.toml
  src/
    main.jo
```

`jo.toml`:

```toml
jo   = ">=1.0.0"
name = "my-agent"

[main]
target = "python"
```

Build and run:

```sh
jo build
jo run
```

## New Library

```sh
jo new my-lib --lib
cd my-lib
```

`jo.toml`:

```toml
jo = ">=1.0.0"

[package]
name    = "my-lib"
version = "0.1.0"
license = "MIT"
```

Run tests:

```sh
jo test
```

## Targets

Set the compilation backend in `[main].target`:

| Value      | Output              |
|------------|---------------------|
| `"python"` | Python script       |
| `"js"`     | JavaScript file     |
| `"ruby"`   | Ruby script         |
| `"native"` | Native executable   |

Default is `"python"` if not specified.

## Multiple Projects in One Directory

Use separate `.toml` files and pass `--spec` explicitly:

```sh
jo build --spec api.toml
jo build --spec app.toml
jo run   --spec app.toml
```
