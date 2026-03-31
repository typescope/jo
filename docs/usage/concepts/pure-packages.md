# Pure Packages

A pure package is a package with:

```toml
runtime = "pure"
```

Pure packages are runtime-independent. They can be reused from Python-targeted
apps, Ruby-targeted apps, and other pure packages. In Jo, they are the default
building block for reusable libraries.

## Security

A pure package cannot on its own reach Python or Ruby code, foreign
dependencies, or runtime-specific escape hatches. In that sense, a pure package
may not do harm on its own.

It can still contain wrong logic, but it does not by itself add runtime-native
capabilities through the package boundary.

The standard library is a pure package. That is the model Jo wants most
reusable packages to follow: pure APIs and pure logic in the middle, with
runtime packages kept at the edge.

## Publishing Rule

Published packages may depend only on pure packages.

That means a pure package may depend on pure packages, and a runtime package may
also depend only on pure packages. Published runtime packages are adapter
packages, not deep dependency layers.

`jo package` enforces this rule.

## Local Development

This rule does not prevent local development with subprojects.

A local subproject package may depend on a runtime package while you are
developing multiple projects together. The restriction applies only when
turning a package into a published `.joy` artifact.
