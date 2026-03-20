# jo register

Register a package in the Jo package index (one-time, per package).

## Usage

```
jo register [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

Generates the registry entry file and opens a PR to `typescope/packages` via `gh`.

Requires `gh` to be installed and authenticated.

## Examples

```sh
jo register
jo register --spec agent-api.toml
```

## Notes

Only needed once per package. After the PR is merged, the hourly scanner picks up all future releases automatically — no further registry interaction required.

Top-level namespace ownership is enforced: if `parsing` is owned by `alice@example.com`, only that owner can register packages under any `parsing.*` namespace.
