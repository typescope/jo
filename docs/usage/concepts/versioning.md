# Versioning

## Major Version Compatibility

Jo uses one simple compatibility model for both the Jo compiler/standard library and package dependencies. The compatibility unit is the **major version**.

Within one major version:

- minor releases are backward compatible
- patch releases are backward compatible

So a requirement like `1.2` means:

- use major line `1`
- require at least version `1.2.0`
- select the latest compatible `1.x.y` release available

## Constraint Syntax

Dependency constraints in `jo.toml` use only `MAJOR.MINOR`:

| Spec    | Meaning |
|---------|---------|
| `"1.2"` | any compatible `1.x.y` version, with version at least `1.2.0` |
| `"2.0"` | any compatible `2.x.y` version, with version at least `2.0.0` |

Jo intentionally does not use richer constraint forms such as:

- `^1.2`
- `~1.2`
- `>=1.2`
- exact pins
- upper bounds

The goal is to give authors one obvious way to express intent and avoid unnecessary constraint complexity.

## Package Versions

Published package versions are still full `MAJOR.MINOR.PATCH` releases:

- `1.2.3`

Constraint syntax stays at `MAJOR.MINOR` because compatibility is defined at the major-version line, not at individual patch releases.

## Compiler Version Constraint

The `jo` field in `jo.toml` uses the same `MAJOR.MINOR` syntax:

```toml
jo = "1.2"
```

The build tool selects the latest installed compatible compiler in that major line.

## Compatibility Discipline

This versioning model depends on a strict compatibility rule:

- breaking change => major bump
- additive backward-compatible change => minor bump
- backward-compatible fix => patch bump

That rule applies to:

- the Jo compiler
- the standard library
- SAST compatibility
- published packages
