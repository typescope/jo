# jo lock

Resolve package dependencies and write the lock file.

## Usage

```sh
jo lock
```

## What It Does

1. Reads the build spec.
2. Resolves registry dependencies for **all** modules, from the current version constraints in `jo.toml`.
3. Selects an exact Jo compiler version and exact package versions.
4. Verifies the selected `.joy` artifacts.
5. Writes `<spec>.lock` with the compiler version plus one key per package containing the exact version and SHA-512 digest.

The lock file covers the whole project. At most one version of each registry package is selected, across every module. If two modules declare incompatible constraints on the same package, `jo lock` fails and reports the module paths on both sides of the conflict.

Source modules are not themselves recorded as packages in the lock file. The registry packages they require are, including those required by modules reached through `path`. `jo lock` loads external project specs and merges their package constraints into the same resolution. A conflict between a local module and an external one is therefore reported here, not at compile time. An external project's own `jo.lock` is ignored while it is consumed as a source dependency.

## Examples

```sh
jo lock
```

## Notes

- If the lock file already exists, `jo lock` rewrites it from a fresh resolution.
- Normal `jo build`, `jo run`, `jo check`, and `jo doc` reuse lock entries when they exist.
- If the lock file is missing, those commands resolve all modules in the project and create it automatically.
- If the lock file exists but does not contain a package needed by the selected module, run `jo lock`.
- Incompatible locked versions or digest mismatches still fail.
