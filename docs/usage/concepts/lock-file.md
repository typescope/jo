# Lock File

Jo records exact package resolution in a lock file beside the build spec:

- `jo.toml` -> `jo.lock`
- `agent-api.toml` -> `agent-api.lock`

## Role

The lock file has two roles:

1. It records the exact compiler and package artifacts selected for a build.
2. It makes later builds reproducible by requiring those exact artifacts again.

`jo.toml` says what versions are acceptable.

`jo.lock` says what was actually chosen.

When a lock file exists:

- the locked compiler version must still satisfy the current `jo` constraint in `jo.toml`
- locked package versions must still satisfy the current dependency constraints
- locked artifact digests must match the actual `.joy` files

If the lock file is missing, the build tool resolves dependencies and writes it.

If the lock file is present, compatible locked entries are reused. Missing entries may be
added automatically. Incompatible locked versions or digest mismatches still fail.

If you want to intentionally refresh exact versions, run `jo lock`.

## Format

The file is TOML with a top-level `jo` entry plus one key per resolved registry package:

```toml
jo = "1.0.0"
greeter-pkg = { version = "1.0.0", sha512 = "4b5f..." }
mustache = { version = "2.3.1", sha512 = "8c12..." }
```

## Fields

The `jo` entry records the exact selected compiler version.

Each package key is the package name. Its inline table contains:

| Field     | Meaning |
|-----------|---------|
| `version` | Exact resolved package version |
| `sha512`  | SHA-512 digest of the selected `.joy` artifact |

## Scope

The lock file records the selected Jo compiler version and all registry-resolved Jo packages.

It does not record:

- local `path` dependencies
- source files
- foreign package managers such as `pip` or RubyGems

## Source Control

Lock files should be committed to source control.

They describe the exact package artifacts used to build and test the source tree.

Published `.joy` packages do not include the lock file, so consumers still resolve their own package set independently.
