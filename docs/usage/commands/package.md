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

## Dependency Behavior

`jo package` packages only the current library. It does **not** bundle dependency
artifacts into the resulting `.joy`.

This follows the same basic model as Rust's `cargo package` / `cargo publish`:
the packaged artifact contains the current package and metadata describing its
dependencies, while consumers resolve and fetch dependencies separately.

For Jo, the packaging rules are:

1. Registry/package dependencies are recorded as dependency metadata in `meta.toml`.
2. Dependency `.joy` files or dependency `.sast` trees are not copied into the archive.
3. Local `path` dependencies are for development only and must not appear in a published package.
4. Therefore, `jo package` should fail if the library still has unresolved local `path` dependencies.

In other words, a packaged library must be publishable on its own, with all direct
dependencies expressed as publishable package dependencies rather than local filesystem paths.

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
