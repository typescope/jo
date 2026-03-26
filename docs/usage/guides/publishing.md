# Publishing a Package

## 1. Prepare the Build Spec

Fill in the publishing metadata in `[package]`:

```toml
jo          = ">=1.0"
name        = "agent-api"

[package]
version     = "1.0.0"
description = "Sandbox agent framework API"
authors     = ["Alice <alice@example.com>"]
license     = "MIT"
homepage    = "https://github.com/alice/agent-api"
keywords    = ["agent", "framework"]
```

## 2. Build the Release Artifact

```sh
jo package
```

This validates the spec, runs `jo test`, then produces the `.joy` artifact:

```
.build/agent-api/release/
  agent-api-v1.0.0.joy
  agent-api-v1.0.0.joy.sha512
```

The version is taken from `[package].version`. Inspect the artifacts before uploading.

## 3. Publish to GitHub Releases

```sh
jo publish
```

Runs `package` and uploads the artifacts to a GitHub Release via `gh`. Requires `gh` to be installed and authenticated.

## 4. Register in the Index (one-time)

The first time you publish a package, register it manually in the registry repository. See the [Registry Reference](../reference/registry.md) for the registration file format and publication rules.

Open a pull request to add the package registration metadata to the registry repository.

## Subsequent Releases

For subsequent releases, only steps 1–3 are needed:

1. Bump `version` in `jo.toml`
2. `jo publish`

The registry daemon detects the new release and updates the canonical release metadata automatically.

## Yanking a Release

Yanking is not specified yet.
