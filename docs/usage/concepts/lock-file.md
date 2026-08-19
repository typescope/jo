# Lock File

Jo records exact package resolution in a lock file beside the build spec. The name comes from the spec's name, so `jo.toml` uses `jo.lock`, and a spec selected with `--spec ../agent-api/jo.toml` uses `../agent-api/jo.lock`.

One project has one lock file. It covers every module in the project.

## Role

The lock file has two roles:

1. It records the exact package artifacts selected for a build.
2. It makes later builds reproducible by requiring those exact artifacts again.

`jo.toml` says what versions are acceptable.

`jo.lock` says what was actually chosen.

When a lock file exists:

- locked package versions must still satisfy the current dependency constraints
- locked artifact digests must match the actual `.joy` files

The compiler version is not locked. `jo.toml` states the required `MAJOR.MINOR` line, and
any patch release within it is accepted.

If the lock file is missing, `jo build`, `jo check`, `jo run`, and `jo doc` resolve all modules in the project and write it.

If the lock file is present, compatible locked entries are reused. Missing entries,
incompatible locked versions, and digest mismatches fail. Run `jo lock` to rewrite
the lock file from a fresh all-module resolution.

If you want to intentionally refresh exact versions, run `jo lock`.

## Scope Is The Project, Not The Module

Writing the lock always resolves every module in the project, whichever command triggers it. `jo build app` on a project with no lock file resolves `app`, `test`, and everything else, then writes one complete lock.

This is why a package version conflict between two modules can surface from a command that only builds one of them. The alternative — locking just the selected module — would write a lock whose contents depend on which module you built first, and the next `jo build test` would fail on entries the first build never wrote.

Package dependency depth is checked separately. `jo lock` checks every module. Build commands check the selected module closure, even when they create the missing lock.

## Format

The file is TOML with one key per resolved registry package:

```toml
greeter-pkg = { version = "1.0.0", sha512 = "4b5f..." }
mustache = { version = "2.3.1", sha512 = "8c12..." }
```

A project with no registry dependencies gets an empty lock file.

## Fields

Each package key is the package name. Its inline table contains:

| Field     | Meaning |
|-----------|---------|
| `version` | Exact resolved package version |
| `sha512`  | SHA-512 digest of the selected `.joy` artifact |

## Scope

The lock file records all registry-resolved Jo packages.

It does not record:

- the Jo compiler version — `jo.toml` states the compatible `MAJOR.MINOR` line, and patch releases within it are interchangeable
- source modules, in this project or reached through `path` — they are source, not artifacts
- source files
- foreign package managers such as `pip` or RubyGems

It *does* record the registry packages those source modules require. A package needed by a module in another project reached through `path` gets an entry in your lock file, because it is part of your build.

The other project's `jo.lock` is not consulted while it is consumed as a source dependency. That lock governs standalone builds of that project. So a package can resolve to one version in your build and to a different version when that project is built on its own. Your build is authoritative for your build.

## Source Control

Lock files should be committed to source control.

They describe the exact package artifacts used to build and test the source tree.

Published `.joy` packages do not include the lock file, so consumers still resolve their own package set independently.
