# jo compile

Raw compiler interface. No project spec required.

## Usage

```
# Compile to .sast files (library/package)
jo compile -sast <file.jo>... [-lib <dir>:<dir>:...] [-ffi python|js|ruby] -d <outdir>

# Compile to executable or script (app)
jo compile <file.jo>... [-lib <dir>:<dir>:...] [-link-lib <dir>:<dir>:...] \
           [-link <src>=<tgt>]... [-python|-js|-ruby|-native] [-open-runtime] -o <output>
```

## Flags

### `-sast` form (library compilation)

| Flag                   | Description |
|------------------------|-------------|
| `-lib <dir>:<dir>:...` | Check library directories (colon-separated) |
| `-ffi python\|js\|ruby` | Declare FFI platform; makes that platform's runtime available as a check library and records `ffi` in `meta.toml` |
| `-d <outdir>`          | Output directory for `.sast` files |

### App compilation

| Flag                      | Description |
|---------------------------|-------------|
| `-lib <dir>:<dir>:...`    | Check library directories |
| `-link-lib <dir>:<dir>:...` | Link library directories (resolves `defer def`s) |
| `-link <src>=<tgt>`       | Wire a specific `defer def` explicitly |
| `-python\|-js\|-ruby\|-native` | Target backend |
| `-open-runtime`           | Make the platform runtime available as a check library |
| `-o <output>`             | Output file |

## Examples

Compile a library to `.sast`:

```sh
jo compile -sast src/API.jo -lib ../core/.build/core/sast -d .build/api/sast
```

Compile an FFI library:

```sh
jo compile -sast src/Runtime.jo -lib .build/api/sast -ffi python -d .build/runtime/sast
```

Compile an app:

```sh
jo compile src/App.jo \
  -lib .build/api/sast \
  -link-lib .build/runtime/sast \
  -link "agentapi.runTask=usertask.runTask" \
  -python -o .build/app/target/app.py
```

## Notes

`jo compile` is a low-level escape hatch for scripts, CI pipelines, or experiments that don't fit the standard project layout. For all normal workflows, prefer the higher-level commands (`jo build`, `jo test`, etc.).
