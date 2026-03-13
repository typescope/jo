# Registry

The Jo package registry is split into two concerns: **name indexing** and **artifact hosting**.

## Name Index

The index is a public GitHub repository (`jo-lang/packages`) that maps short package names to repository URLs and release metadata. It contains two files per package:

- `<name>.toml` — human-maintained: package identity, owner, repository URL
- `<name>.releases.jsonl` — program-maintained: one record per release with artifact URLs and sha512 digests

A local SQLite snapshot (`~/.jo/index.db`) is downloaded from GitHub Pages for fast querying:

```
https://jo-lang.github.io/packages/index.db
```

The index is eventually consistent — updated hourly by a scanner that detects new GitHub releases automatically.

## Artifact Hosting

Authors host their own artifacts via GitHub Releases (or any other platform). The registry records the canonical artifact URL and sha512 digest for each release. `jo build` fetches the `.joy` file and verifies its digest independently of the source.

## Versioning

Jo uses semantic versioning (`MAJOR.MINOR.PATCH`) with Cargo-style range syntax:

| Spec          | Meaning           |
|---------------|-------------------|
| `"^1.2.0"`    | `>=1.2.0, <2.0.0` |
| `"~1.2.0"`    | `>=1.2.0, <1.3.0` |
| `">=1.0, <2"` | explicit range    |
| `"1.2.0"`     | exact version     |

## Dependency Resolution

The resolver uses **Minimum Version Selection (MVS)**:

1. Collect all version constraints across the full dependency graph
2. For each package, select the maximum of all stated lower bounds
3. Verify the selected version satisfies every upper bound
4. Fail with a clear error if any constraint is violated

MVS is deterministic and requires no backtracking. Adding a dependency never silently upgrades unrelated packages. One version of each package is selected — matching Jo's single-namespace compilation model.

## Mirrors

Mirrors serve `.joy` artifacts under a standardised path:

```
<mirror-base>/<name>/<version>/<filename>
```

Mirrors are declared in `jo.toml` (shared by the team) or `~/.jo/config.toml` (user-specific). They are never stored in the registry — a compromised registry entry cannot redirect downloads. sha512 verification always runs regardless of source.

```toml
# ~/.jo/config.toml
[mirrors]
urls = [
  "https://cache.jo-lang.org/jo",
  "https://mirror.mycompany.com/jo-packages",
]
```
