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

`jo test` reuses compatible lock entries and may refresh missing ones automatically. It fails
when an existing locked entry is incompatible with the current build.

Exit code is non-zero on failure.

## Test Target Resolution

The backend for running tests is resolved in order:

1. Explicit `[test].target`
2. `[main].target`
3. Inherited from `package.runtime` (when `package.runtime != "pure"`)
4. Inferred from runtime-constrained deps in `[test.dependencies]` (all must agree)
5. Default: `"python"`

## Examples

```sh
jo test
jo test --spec agent-api.toml
```
