# Pre-Releases

Jo does not support pre-release package versions yet, but the design is settled
enough to describe the intended behavior.

## Version Form

Stable package versions use:

- `MAJOR.MINOR.PATCH`

Planned pre-release versions will extend that with a suffix:

- `1.2.0-alpha1`
- `1.2.0-beta1`
- `1.2.0-rc1`

Each pre-release remains a distinct concrete release version. Normal dependency
constraints stay `MAJOR.MINOR`, such as `1.2`. Pre-release suffixes are only
for concrete package versions, root-level `[pinning]`, and the exact version
recorded in `jo.lock`.

## Selection And Pinning

Default dependency resolution will ignore pre-releases.

So if the registry contains:

- `1.2.0-alpha1`
- `1.2.0-rc1`
- `1.2.0`

normal resolution still picks only stable releases.

The planned way to use a pre-release is through root-level pinning:

```toml
[pinning]
mustache = "1.2.0-rc1"
```

This keeps pre-release usage explicit, local to the root build, and out of
published package metadata.

## Registry And Lock Files

The registry may contain pre-release package versions alongside stable
versions.

If a root build pins a pre-release and resolution succeeds, `jo.lock` may record
that exact pre-release version too. `jo.lock` only records the chosen exact
version. It does not change selection policy.
