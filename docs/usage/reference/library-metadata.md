# Library Metadata Reference

Library metadata is declared in the `[package]` section of `jo.toml`. Its presence marks the build as a **library** (produces `.joy`). Absent means **app** build.

| Field         | Type            | Required | Description |
|---------------|-----------------|----------|-------------|
| `name`        | string          | yes      | Package name. Lowercase, hyphens allowed. Unique in the registry. |
| `version`     | string          | yes      | Semantic version (`MAJOR.MINOR.PATCH`). |
| `description` | string          | no       | One-line summary. |
| `authors`     | array of string | no       | `["Name <email>"]` format. |
| `license`     | string          | no       | SPDX identifier, e.g. `"MIT"`, `"Apache-2.0"`. |
| `homepage`    | string          | no       | URL of the project website or documentation. |
| `keywords`    | array of string | no       | Up to 5 terms; used by `jo search`. |
| `ffi`         | string          | no       | Optional assertion: `"none"`, `"python"`, `"ruby"`. Verified by `jo build-release`. Computed from source and deps if absent. |

## Example

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
