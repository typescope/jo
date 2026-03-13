# jo publish

Build and upload release artifacts to GitHub Releases.

Note: _Requires `gh` to be installed and authenticated (`gh auth login`)_.

## Usage

```
jo publish [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

Runs [`jo build-release`](build-release.md) then uploads all files from `.build/<stem>/release/` to a GitHub Release via `gh`.

## Examples

```sh
jo publish
jo publish --spec agent-api.toml
```

## Notes

Register the package first with [`jo register`](register.md) before publishing for the first time. Subsequent releases are picked up automatically by the hourly registry scanner.
