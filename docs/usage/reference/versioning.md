# Versioning

Jo uses a two-level versioning scheme:

- **Package versions** in `jo.toml` use `MAJOR.MINOR` — e.g. `"1.2"`.
- **Resolved versions** in the lock file and inside `.joy` archives use `MAJOR.MINOR.PATCH` — e.g. `"1.2.3"`.

Authors declare their package at `MAJOR.MINOR`; the registry assigns the patch number when a release is created. Lock files record the exact `MAJOR.MINOR.PATCH` for reproducible builds.

## Dependency Constraint Syntax

Dependency constraints in `jo.toml` use Cargo-style range syntax with `MAJOR.MINOR` versions:

| Spec          | Meaning                   |
|---------------|---------------------------|
| `"^1.2"`      | `>=1.2.0, <2.0.0`         |
| `"~1.2"`      | `>=1.2.0, <1.3.0`         |
| `">=1.0"`     | at least 1.0              |
Breaking changes require a MAJOR bump. Non-breaking additions increment MINOR.

## Compiler Version Constraint

The `jo` field in `jo.toml` uses the same `MAJOR.MINOR` constraint syntax:

```toml
jo = ">=1.2"
```

This is a constraint, not an exact version — the build tool selects the highest installed compiler version that satisfies it.
