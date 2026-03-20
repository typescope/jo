# jo build-release

Build release artifacts for publishing.

## Usage

```
jo build-release [--spec <file.toml>]
```

Only valid for library builds.

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

1. Validates the build spec
2. Compiles and generate `.sast` files to `.build/<stem>/sast/`
3. Generates `meta.toml` and packages `.sast` files into a `.joy` archive under `.build/<stem>/release/`

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
