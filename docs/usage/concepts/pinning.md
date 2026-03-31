# Pinning

Pinning lets the root build spec force an exact package version when normal
compatibility-line resolution needs a manual override.

Example:

```toml
[pinning]
mustache = "1.2.3"
```

## Role

Normal Jo dependency constraints stay simple:

- `1.2` means “major line `1`, at least `1.2.0`”
- the resolver normally picks the latest compatible release

Pinning is the explicit escape hatch. It says:

- for this root build only
- if package `mustache` is selected
- it must be exactly `1.2.3`

## Scope

Pinning is allowed only in the root build spec:

- allowed in `jo.toml`
- not allowed in `meta.toml`
- not exported transitively by published packages

This keeps published package metadata focused on compatibility rather than local
resolution choices.

## Syntax

Pinning uses exact concrete versions in `MAJOR.MINOR.PATCH` format:

```toml
[pinning]
mustache = "1.2.3"
json = "2.0.1"
```

Unlike normal dependency constraints, pinning does not use `MAJOR.MINOR`.

## Resolution Semantics

Pins are hard requirements.

Resolution works like this:

1. collect the normal dependency constraints
2. if a package is pinned, require the pinned exact version
3. if that exact version is compatible, use it
4. otherwise fail explicitly

Jo never silently ignores a conflicting pin.

## Error Cases

Resolution fails explicitly when:

- the pinned version does not exist
- the pinned version does not satisfy dependency constraints
- the pinned version requires an incompatible Jo compiler version

If an existing `jo.lock` records a different version than the pin, `jo lock`
refreshes the lock to match the pin.

## Unused Pins

If `[pinning]` contains a package name that is never selected during resolution,
Jo issues a warning:

```text
warning: unused [pinning] entry mustache = "1.2.3"
```

This helps catch typos and stale overrides.

## When To Use It

Pinning should be rare.

Use it when:

- you need to test a specific concrete package release
- you need a temporary project-level override
- you need explicit control over one package version in a top-level build

Do not use pinning as the normal way to describe dependency compatibility. That
is what the usual `MAJOR.MINOR` dependency syntax is for.
