# jo doc

Generate project API documentation from the main module sources.

## Usage

```
jo doc [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## Output

Generated HTML files are written to `.build/<name>/doc/`.

`jo doc` uses the project's main sources and reads documentation settings from `jo.toml`:

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

For the primitive source-file interface, use [`jo compile --doc`](compile.md).
