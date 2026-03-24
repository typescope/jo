# jo package

Build a distributable package for publishing.

## Usage

```
jo package [--spec <file.toml>]
```

Only valid for library builds.

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

1. Validates the build spec
2. Compiles and generate `.sast` files to `.build/<name>/jo-<version>/sast/`
3. Generates `meta.toml` and packages `.sast` files into a `.joy` archive under `.build/<name>/release/`

Nothing is uploaded. Inspect the artifacts before publishing.

## Output

```
.build/agent-api/release/
  agent-api-v1.0.0.joy
  agent-api-v1.0.0.joy.sha512
```

The version is taken from `[package].version` in the build spec.

## Examples

```sh
jo package
jo package --spec agent-api.toml
```
