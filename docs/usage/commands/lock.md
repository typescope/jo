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
3. Chooses exact package versions and verifies the selected `.joy` artifacts.
4. Writes `<spec>.lock` with the exact package versions and SHA-512 digests.

Path dependencies are not recorded in the lock file.

## Examples

```sh
jo lock
jo lock --spec agent-api.toml
```

## Notes

- If the lock file already exists, `jo lock` rewrites it from a fresh resolution.
- Normal `jo build`, `jo run`, and `jo test` use the lock file strictly when it exists.
- If the lock file is missing, those commands resolve dependencies and create it automatically.
- If the lock file is present but stale, those commands fail instead of silently changing it.
