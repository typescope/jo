# jo doc

Generate API documentation from a module.

## Usage

```
jo doc [module]
```

If `module` is omitted, Jo documents the project default module.

## Output

Generated HTML files are written to `.build/<module>/doc/`.
On success, `jo doc` prints that output directory.

`jo doc` uses the selected module's sources and reads documentation settings from `jo.toml`:

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
jo doc api
```

The primitive source-file interface, [`jo compile --doc`](compile.md), is experimental.
