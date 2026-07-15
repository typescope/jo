# jo package

Build a distributable package for publishing.

## Usage

```
jo package [module]
```

The selected module must define `[module.<id>.package]`. If `module` is omitted, Jo packages the project default module.

## What It Does

1. Validates the build spec
2. Compiles and generate `.sast` files to `.build/<module>/jo-<version>/sast/`
3. Generates `meta.toml` and packages `.sast` files into a `.joy` archive under `.build/<module>/release/`
4. Generates a companion source archive containing the original `.jo` sources

Nothing is uploaded. Inspect the artifacts before publishing.

## Dependency Behavior

`jo package` packages only the current library. It does **not** bundle dependency
artifacts into the resulting `.joy`.

This follows the same basic model as Rust's `cargo package` / `cargo publish`:
the packaged artifact contains the current package and metadata describing its
dependencies, while consumers resolve and fetch dependencies separately.

For source code, Jo follows Java's model rather than embedding sources inside the
compiled package:

1. `<name>-v<version>.joy` contains compiled `.sast` files and `meta.toml`
2. `<name>-v<version>-sources.zip` contains the original `.jo` source files
3. tools can inspect or attach sources separately without changing the runtime package format

For Jo, the packaging rules are:

1. Registry/package dependencies are recorded as dependency metadata in `meta.toml`.
2. Dependency `.joy` files or dependency `.sast` trees are not copied into the archive.
3. Direct source module dependencies are recorded as package dependencies, using the dependency module's `[module.<id>.package]` metadata.
4. `link = true` dependencies are omitted from `meta.toml`.
5. Published packages may depend only on `pure` registry packages. A runtime package may be published, but its published registry dependencies must still be `pure`.

In other words, source module dependencies must also be publishable modules unless they are link-only dependencies.

## Output

```
.build/api/release/
  agent-api-v1.0.0.joy
  agent-api-v1.0.0.joy.sha512
  agent-api-v1.0.0-sources.zip
  agent-api-v1.0.0-sources.zip.sha512
```

The version is taken from `[module.<id>.package].version` in the build spec.

## Examples

```sh
jo package
jo package api
```
