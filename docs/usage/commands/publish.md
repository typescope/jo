# jo publish

Upload release artifacts to a draft GitHub Release.

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

Runs [`jo build-release`](build-release.md) then uploads all files from `.build/<stem>/release/` to a **draft** GitHub Release via `gh`. The draft is not published — you can add release notes and publish it manually when ready.

## Examples

```sh
jo publish
jo publish --spec agent-api.toml
```

## Notes

Register the package first with [`jo register`](register.md) before publishing for the first time. Subsequent releases are picked up automatically by the hourly registry scanner.
