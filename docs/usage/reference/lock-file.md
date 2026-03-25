# Lock File

Jo records exact package resolution in a lock file beside the build spec:

- `jo.toml` -> `jo.lock`
- `agent-api.toml` -> `agent-api.lock`

## Role

The lock file has two roles:

1. It records the exact package artifacts selected for a build.
2. It makes later builds reproducible by requiring those exact artifacts again.

`jo.toml` says what versions are acceptable.

`jo.lock` says what was actually chosen.

When a lock file exists:

- `jo build`, `jo run`, and `jo test` use it strictly.
- the locked version must still satisfy the current version constraint in `jo.toml`
- the locked artifact digest must match the actual `.joy` file

If the lock file is missing, the build tool resolves dependencies and writes it.

If the lock file is present but stale, the build fails.

If you want to intentionally refresh exact versions, run `jo lock`.

## Format

The file is TOML with one `[[package]]` entry per resolved registry package:

```toml
[[package]]
name = "greeter-pkg"
version = "1.0.0"
sha512 = "4b5f..."

[[package]]
name = "mustache"
version = "2.3.1"
sha512 = "8c12..."
```

## Fields

| Field     | Meaning |
|-----------|---------|
| `name`    | Package name |
| `version` | Exact resolved package version |
| `sha512`  | SHA-512 digest of the selected `.joy` artifact |

## Scope

The lock file records only registry-resolved Jo packages.

It does not record:

- local `path` dependencies
- source files
- compiler version selection
- foreign package managers such as `pip` or RubyGems

## Libraries vs Apps

- Apps should usually commit the lock file.
- Libraries should usually ignore it and let consumers resolve their own exact package set.
