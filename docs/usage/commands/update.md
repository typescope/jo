# jo update

Re-resolve dependencies and rewrite the lock file.

## Usage

```
jo update [--spec <file.toml>] [<pkg>...]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |
| `<pkg>...`      | Packages to update. Default: all.      |

## Examples

```sh
jo update                  # re-resolve all dependencies
jo update mustache               # update only mustache
jo update mustache agent-api     # update specific packages
```

## Notes

Without arguments, discards the lock file and re-runs MVS resolution from scratch against the current version constraints in `jo.toml`. Warns if any resolved version is yanked.

With package names, re-resolves only those packages while keeping the rest of the lock file intact.
