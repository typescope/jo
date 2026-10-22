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

To use a pre-release, pin a version that appears in the package's registry release log. The following is a template; replace both placeholders with the published package name and pre-release version:

```text
[pinning]
<package-name> = "<published-prerelease-version>"
```

Pinning is local to the root project and not propagated to published packages.

## Lock Files

When a pre-release is selected via pinning, `jo.lock` records that exact
version. The lock file does not change selection policy for unpinned packages.
