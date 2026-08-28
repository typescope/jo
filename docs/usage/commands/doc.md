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

`jo doc` uses the selected module's sources and reads `doc-options` from `jo.toml`:

```toml
[module.api]
kind = "lib"
src = ["src/"]
doc-options = [
  "--title", "Agent API",
  "--readme", "README.md",
]
```

The options are passed verbatim to `jo compile --doc`. They apply only to
documentation generation; the module's `compile-options` do not apply to
`jo doc`. See the [compiler options reference](../reference/compiler-options.md#documentation-options)
for available documentation options.

The documentation header shows the module's version, taken from
`[module.<id>.package].version`. Modules that declare no package show
`version unknown`.

The generated documentation is a self-contained directory that can be opened directly in a browser — no web server required.

## Examples

```sh
jo doc
jo doc api
```

The primitive source-file interface, [`jo compile --doc`](compile.md), is experimental.
