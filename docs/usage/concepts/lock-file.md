# Lock File

Jo records exact package resolution in a lock file beside the build spec. The name comes from the spec's name, so `jo.toml` uses `jo.lock`, and a spec selected with `--spec ../agent-api/jo.toml` uses `../agent-api/jo.lock`.

One project has one lock file. It covers every module in the project.

## Role

The lock file has two roles:

1. It records the exact compiler and package artifacts selected for a build.
2. It makes later builds reproducible by requiring those exact artifacts again.

`jo.toml` says what versions are acceptable.

`jo.lock` says what was actually chosen.

When a lock file exists:

- the locked compiler version is reused if it still satisfies the current `jo` constraint in `jo.toml`
- if the locked compiler version no longer satisfies `jo.toml`, it is treated as stale and replaced by a fresh compatible compiler selection
- locked package versions must still satisfy the current dependency constraints
- locked artifact digests must match the actual `.joy` files

If the lock file is missing, `jo build`, `jo check`, `jo run`, and `jo doc` resolve all modules in the project and write it.

If the lock file is present, compatible locked entries are reused. Missing entries,
incompatible locked versions, and digest mismatches fail. Run `jo lock` to rewrite
the lock file from a fresh all-module resolution.

If you want to intentionally refresh exact versions, run `jo lock`.

## Scope Is The Project, Not The Module

Writing the lock always resolves every module in the project, whichever command triggers it. `jo build app` on a project with no lock file resolves `app`, `test`, and everything else, then writes one complete lock.

This is why a package version conflict between two modules can surface from a command that only builds one of them. The alternative — locking just the selected module — would write a lock whose contents depend on which module you built first, and the next `jo build test` would fail on entries the first build never wrote.

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

- source modules, in this project or reached through `path` — they are source, not artifacts
- source files
- foreign package managers such as `pip` or RubyGems

It *does* record the registry packages those source modules require. A package needed by a module in another project reached through `path` gets an entry in your lock file, because it is part of your build.

The other project's `jo.lock` is not consulted while it is consumed as a source dependency. That lock governs standalone builds of that project. So a package can resolve to one version in your build and to a different version when that project is built on its own. Your build is authoritative for your build.

## Source Control

Lock files should be committed to source control.

They describe the exact package artifacts used to build and test the source tree.

Published `.joy` packages do not include the lock file, so consumers still resolve their own package set independently.
