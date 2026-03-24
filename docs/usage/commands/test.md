# jo test

Run tests.

## Usage

```
jo test [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

1. Compiles `[main].src` as a library into `.build/<name>/jo-<version>/sast/`
2. Compiles `[test].src` as an app against the main library and `[test.dependencies]`
3. Runs the resulting executable; the test framework drives discovery and reporting

Exit code is non-zero on failure.

## Test Target Resolution

The backend for running tests is resolved in order:

1. Explicit `[test].target`
2. `[main].target`
3. Inherited from `main.ffi` (when `main.ffi != "none"`)
4. Inferred from FFI deps in `[test.dependencies]` (all must agree)
5. Default: `"python"`

::: info
See [jo deps](deps.md) for how to install foreign package dependencies and configure the runtime environment.
:::
## Examples

```sh
jo test
jo test --spec agent-api.toml
```
