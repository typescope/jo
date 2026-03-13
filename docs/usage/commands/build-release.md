# jo build-release

Build release artifacts for publishing.

## Usage

```
jo build-release [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

1. Validates the build spec
2. Runs `jo test`
3. Compiles the `.joy` package
4. Writes artifacts to `.build/<stem>/release/`

Nothing is uploaded — inspect the artifacts before publishing.

## Output

```
.build/agent-api/release/
  agent-api-v1.0.0.joy
  agent-api-v1.0.0.joy.sha512
```

The version is taken from `[package].version` in the build spec.

## Examples

```sh
jo build-release
jo build-release --spec agent-api.toml
```

## Notes

Only valid for library builds. Use [`jo publish`](publish.md) to upload after inspecting.
