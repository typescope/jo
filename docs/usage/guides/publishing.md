# Publishing a Package

## 1. Prepare the Module

Add `[module.<id>.package]` to the module you want to publish:

```toml
jo = "1.0"

[module.api]
kind = "lib"
src = ["src/"]

[module.api.package]
name = "agent-api"
version = "1.0.0"
description = "Sandbox agent framework API"
authors = ["Alice <alice@example.com>"]
license = "MIT"
homepage = "https://github.com/alice/agent-api"
keywords = ["agent", "framework"]
```

## 2. Build the Release Artifact

```sh
jo package api
```

This writes artifacts under `.build/api/release/`:

```
agent-api-v1.0.0.joy
agent-api-v1.0.0-sources.zip
agent-api-v1.0.0.joy.sha512
agent-api-v1.0.0-sources.zip.sha512
```

The version is taken from `[module.api.package].version`.

## 3. Publish

Create and push a release tag, then upload the artifacts:

```sh
git tag v1.0.0
git push origin v1.0.0
gh release create v1.0.0 \
  .build/api/release/agent-api-v1.0.0.joy \
  .build/api/release/agent-api-v1.0.0-sources.zip \
  --verify-tag \
  --title "v1.0.0" \
  --notes-from-tag
```

This requires `gh` to be installed and authenticated.

## 4. Register in the Index (one-time)

The first time you publish a package, register it manually in the registry repository. See the [Registry Reference](../reference/registry.md) for the registration file format and publication rules.

Open a pull request to add the package registration metadata to the registry repository.

## Subsequent Releases

For subsequent releases, only steps 1–3 are needed:

1. Bump `version` in `[module.api.package]`
2. `jo package api`
3. create and push `v<version>`
4. `gh release create ...`

The registry daemon detects the new release and updates the canonical release metadata automatically.

## Yanking a Release

Yanking is not specified yet.
