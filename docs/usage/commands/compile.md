# jo compile

Raw compiler interface. No project spec required.

## Usage

```
# Type-check and emit .sast files
jo compile [--sast <dir>] <file.jo>... [--lib <dir>]...

# Compile to executable or script
jo compile --python|--ruby|--js|--stack|--reg [--sast <dir>] <file.jo>... \
           [--lib <dir>]... [--link-lib <dir>]... [--link <src>=<tgt>]... -o <output>

# Generate documentation from source files
jo compile --doc [--out <dir>] [--title <name>] [--include-private] [--include-source] \
           <file.jo>...
```

Without a backend flag, the compiler type-checks only. With a backend flag, it produces an executable or script. `--sast <dir>` is optional in both cases — if present, `.sast` files are written to `<dir>` alongside the primary output.

`--doc` switches the compiler into primitive documentation mode. This is the low-level interface for generating docs directly from source files. For normal project workflows, prefer [`jo doc`](doc.md).

## Flags

### Common

| Flag                    | Description |
|-------------------------|-------------|
| `--sast <dir>`          | Also emit `.sast` files to `<dir>` |
| `--lib <dir>`           | Check library directory (can be repeated) |
| `--no-stdlib`           | Disable automatic stdlib loading |

### Documentation

| Flag | Description |
|------|-------------|
| `--doc` | Generate documentation instead of normal compile output |
| `--out <dir>` | Documentation output directory |
| `--title <name>` | Documentation title |
| `--include-private` | Include private symbols |
| `--include-source` | Embed source code in output |

### App compilation

| Flag                      | Description |
|---------------------------|-------------|
| `--python\|--ruby\|--js`  | Target scripting backend |
| `--stack\|--reg`          | Target native backend (experimental) |
| `--link-lib <dir>`        | Link library directory (resolves `defer def`s, can be repeated) |
| `--link <src>=<tgt>`      | Wire a specific `defer def` explicitly (can be repeated) |
| `-o <output>`             | Output file path |

## Examples

Type-check a library and emit `.sast`:

```sh
jo compile --sast .build/api/sast src/API.jo --lib ../core/.build/core/sast
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

## Notes

`jo compile` is a low-level escape hatch for scripts, CI pipelines, or experiments that don't fit the standard project layout. For normal project workflows, prefer the higher-level commands such as `jo build`, `jo test`, and [`jo doc`](doc.md).
