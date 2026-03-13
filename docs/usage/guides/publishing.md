# Publishing a Package

## 1. Prepare the Build Spec

Fill in the publishing metadata in `[package]`:

```toml
jo = ">=1.0.0"

[package]
name        = "agent-api"
version     = "1.0.0"
description = "Sandbox agent framework API"
authors     = ["Alice <alice@example.com>"]
license     = "MIT"
homepage    = "https://github.com/alice/agent-api"
keywords    = ["agent", "framework"]
```

## 2. Build the Release Artifact

```sh
jo build-release
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

Runs `build-release` and uploads the artifacts to a GitHub Release via `gh`. Requires `gh` to be installed and authenticated.

## 4. Register in the Index (one-time)

The first time you publish a package, register it in `jo-lang/packages`:

```sh
jo register
```

This generates the registry entry and opens a PR to `jo-lang/packages` via `gh`. Once merged, the scanner picks up future releases automatically — no further action needed.

## Subsequent Releases

For subsequent releases, only steps 1–3 are needed:

1. Bump `version` in `jo.toml`
2. `jo publish`

The hourly scanner detects the new GitHub release and updates the index automatically.

## Yanking a Release

If a published version has a critical bug or security issue, mark it as yanked by submitting a PR to `jo-lang/packages` adding `"yanked": true` to the relevant record in `<name>.releases.jsonl`. The resolver will skip yanked versions during range resolution; existing locked builds continue to work.
