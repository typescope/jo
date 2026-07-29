# jo compile

Raw compiler interface. No project spec required.

## Usage

```
# Type-check and emit .sast files
jo compile [--sast <dir>] <file.jo>... [--lib <dir>]...

# Compile to executable or script
jo compile --python|--ruby|--js [--sast <dir>] <file.jo>... \
           [--lib <dir>]... [--link-lib <dir>]... [--link <src>=<tgt>]... -o <output>

# Generate HTML documentation from source files (experimental)
jo compile --doc [--out <dir>] [--title <name>] [--readme <file>] \
           [--include-private] [--include-source] <file.jo>...

# Query API information as JSON (experimental)
jo compile --query <selectors> [<file.jo>...] [--lib <dir>]...
```

Without a backend flag, the compiler type-checks only. With a backend flag, it produces an executable or script. `--sast <dir>` is optional in both cases — if present, `.sast` files are written to `<dir>` alongside the primary output.

`--doc` is an experimental low-level interface for generating docs directly from
source files. It may change. For normal project workflows, prefer [`jo doc`](doc.md).

## Options

This page lists the main user-facing options. For the full low-level option set,
including stricter checking and compiler-development flags, see
[Compiler Options](../reference/compiler-options.md).

### Common

| Flag                    | Description |
|-------------------------|-------------|
| `--sast <dir>`          | Also emit `.sast` files to `<dir>` |
| `--lib <dir>`           | Check library directory (can be repeated) |
| `--use-runtime-api <runtime>` | Make the selected runtime API available as a check library. |
| `--no-stdlib`           | Disable automatic stdlib loading |

### Documentation

Experimental.

| Flag | Description |
|------|-------------|
| `--doc` | Generate HTML documentation instead of normal compile output |
| `--out <dir>` | Documentation output directory |
| `--title <name>` | Documentation title |
| `--readme <file>` | Markdown file to use as the generated documentation home page |
| `--include-private` | Include private symbols |
| `--include-source` | Embed source code in output |

### Query

Experimental.

| Flag | Description |
|------|-------------|
| `--query <selectors>` | Query comma-separated symbols or source files and write a JSON array to stdout |

Selectors may name symbols or source files with `file:<path>`.
Symbol selectors are dot-separated names resolved from the root namespace, such
as `jo.List.map`. Positional source files are optional. With no source files,
the query searches the loaded SAST libraries, including stdlib, any `--lib`
paths, and the runtime API selected by `--use-runtime-api`.

A file selector may use the recorded source path, an absolute path, or a basename:

```sh
jo compile --query file:Byte.jo
```

Query output includes public symbols only.

### App compilation

| Flag                      | Description |
|---------------------------|-------------|
| `--ruby`                  | Target Ruby (default) |
| `--python`                | Target Python |
| `--js`                    | Target JavaScript (experimental) |
| `--link-lib <dir>`        | Link library directory (resolves `defer def`s, can be repeated) |
| `--link <src>=<tgt>`      | Wire a specific `defer def` explicitly (can be repeated) |
| `-o <output>`             | Output file path |

When `--use-runtime-api` matches the selected app backend, the compiler uses that runtime API through the check-library path and suppresses the backend's default runtime link library.

## Examples

Type-check a library and emit `.sast`:

```sh
jo compile --sast .build/api/sast src/API.jo --lib ../core/.build/core/sast
```

Type-check a Python runtime library and emit `.sast`:

```sh
jo compile --sast .build/runtime/sast --use-runtime-api python src/Runtime.jo
```

Compile a Python app:

```sh
jo compile --python src/App.jo \
  --lib .build/api/sast \
  --link-lib .build/runtime/sast \
  --link "agentapi.runTask=usertask.runTask" \
  -o .build/app/target/app.py
```

Compile a Python app and also emit `.sast`:

```sh
jo compile --python --sast .build/app/sast src/App.jo \
  --lib .build/api/sast \
  --link-lib .build/runtime/sast \
  -o .build/app/target/app.py
```

Generate docs directly from source files:

```sh
jo compile --doc lib/Core.jo lib/List.jo \
  --out stdlib-doc \
  --title "Jo Standard Library"
```

Query selected symbols from source:

```sh
jo compile --query 'MyAPI,jo.List' src/API.jo
```

Query stdlib API information from SAST files only:

```sh
jo compile --query jo.List.map
```

Query a standard-library source file by basename:

```sh
jo compile --query file:Byte.jo
```

## Notes

`jo compile` is a low-level escape hatch for scripts, CI pipelines, or experiments that don't fit the standard project layout. For normal project workflows, prefer the higher-level commands such as `jo build`, `jo run <module>`, and [`jo doc`](doc.md).

If a module declares `enable-ffi = true`, `jo build` passes the `--use-runtime-api` value matching that module's `platform` automatically.
