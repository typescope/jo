# jo lock

Resolve package dependencies and write the lock file.

## Usage

```sh
jo lock [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

1. Reads the build spec.
2. Resolves registry dependencies from the current version constraints in `jo.toml`.
3. Selects an exact Jo compiler version and exact package versions.
4. Verifies the selected `.joy` artifacts.
5. Writes `<spec>.lock` with the compiler version plus one key per package containing the exact version and SHA-512 digest.

Path dependencies are not recorded in the lock file.

## Examples

```sh
jo lock
jo lock --spec agent-api.toml
```

## Notes

- If the lock file already exists, `jo lock` rewrites it from a fresh resolution.
- Normal `jo build`, `jo run`, `jo check`, `jo test`, and `jo doc` reuse compatible lock entries when they exist.
- If the lock file is missing, those commands resolve dependencies and create it automatically.
- Missing compatible entries may be added automatically.
- Incompatible locked versions or digest mismatches still fail.
