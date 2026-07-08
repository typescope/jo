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
On success, `jo doc` prints that output directory.

`jo doc` uses the project's main sources and reads documentation settings from `jo.toml`:

```toml
[doc]
title = "Agent API"
readme = "README.md"
include-private = false
include-source = false
```

| Field             | Description                                                  |
|-------------------|--------------------------------------------------------------|
| `title`           | Project title shown in the documentation header.             |
| `readme`          | Markdown file used as the home page. Optional.               |
| `include-private` | Include private symbols. Default: `false`.                   |
| `include-source`  | Embed source locations. Default: `false`.                    |

The generated documentation is a self-contained directory that can be opened directly in a browser — no web server required.

## Examples

```sh
jo doc
jo doc --spec agent-api.toml
```

The primitive source-file interface, [`jo compile --doc`](compile.md), is experimental.
