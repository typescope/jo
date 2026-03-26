# jo doc

Generate API documentation.

## Usage

```
jo doc [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## Output

Generated HTML files are written to `.build/<name>/doc/` by default.

Documentation settings are configured in `jo.toml`:

```toml
[doc]
title = "Agent API"
include-private = false
include-source = false
```

## Examples

```sh
jo doc
jo doc --spec agent-api.toml
```
