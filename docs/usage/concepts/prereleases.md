# Pre-Releases

Pre-release package versions follow the form `MAJOR.MINOR.PATCH-<modifier>`:

```
1.2.0-alpha1
1.2.0-beta1
1.2.0-rc1
```

The modifier is an alphanumeric suffix. Pre-release versions sort below their
corresponding stable release — `1.2.0-rc1` is less than `1.2.0`.

## Default Resolution

Normal dependency resolution ignores pre-releases. If the registry contains:

```
1.2.0-alpha1
1.2.0-rc1
1.2.0
```

a `1.2` constraint picks `1.2.0`, not the pre-releases.

## Opting In With Pinning

To use a pre-release, pin it explicitly in the root build spec:

```toml
[pinning]
mustache = "1.2.0-rc1"
```

Pinning is local to the root project and not propagated to published packages.

## Lock Files

When a pre-release is selected via pinning, `jo.lock` records that exact
version. The lock file does not change selection policy for unpinned packages.
