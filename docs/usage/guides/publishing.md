# Publishing a Package

This guide uses the published [ansi](https://github.com/liufengyun/ansi) library as an example. When publishing your own library, use your own package name, repository, and release version.

## 1. Prepare the Module

Add `[module.<id>.package]` to the module you want to publish:

```toml
jo = "0.14"

[module.ansi]
kind = "lib"
src = ["src/"]

[module.ansi.package]
name = "ansi"
version = "0.1.1"
description = "ANSI terminal escape codes for styled text output"
authors = ["Fengyun Liu <fengyun.liu.cs@gmail.com>"]
license = "MIT"
homepage = "https://github.com/liufengyun/ansi"
```

## 2. Build the Release Artifact

```sh
jo package ansi
```

This writes artifacts under `.build/ansi/release/`:

```
ansi-v0.1.1.joy
ansi-v0.1.1-sources.zip
ansi-v0.1.1.joy.sha512
ansi-v0.1.1-sources.zip.sha512
```

The version is taken from `[module.ansi.package].version`.

## 3. Publish

Create and push a release tag, then upload the artifacts:

```sh
git tag v0.1.1
git push origin v0.1.1
gh release create v0.1.1 \
  .build/ansi/release/ansi-v0.1.1.joy \
  .build/ansi/release/ansi-v0.1.1.joy.sha512 \
  .build/ansi/release/ansi-v0.1.1-sources.zip \
  .build/ansi/release/ansi-v0.1.1-sources.zip.sha512 \
  --verify-tag \
  --title "v0.1.1" \
  --notes-from-tag
```

This requires `gh` to be installed and authenticated.

## 4. Register in the Index (one-time)

The first time you publish a package, register it manually in the registry repository. See the [Registry Reference](../reference/registry.md) for the registration file format and publication rules.

Open a pull request to add the package registration metadata to the registry repository.

## Subsequent Releases

For subsequent releases, only steps 1–3 are needed:

1. Bump `version` in `[module.ansi.package]`
2. `jo package ansi`
3. create and push `v<version>`
4. `gh release create ...`

The registry daemon detects the new release and updates the canonical release metadata automatically.

## Yanking a Release

Yanking is not specified yet.
