# Pure Packages

A pure package is a package built from a module with:

```toml
platform = "pure"
```

This is the default for lib modules, and it is what `meta.toml` records when a
lib module names no platform.

Pure packages are platform-independent. They can be reused from Python apps,
Ruby apps, and other pure packages. In Jo, they are the default building block
for reusable libraries.

## Security

A pure package cannot on its own reach Python or Ruby code, foreign
dependencies, or runtime-specific escape hatches. `platform = "pure"` leaves no
FFI API to enable, so `enable-ffi = true` on a pure module is an error rather
than a way in. In that sense, a pure package may not do harm on its own.

Reaching a runtime is a capability a module has to ask for. Even a
platform-bound module gets no FFI API unless it sets `enable-ffi = true`, so
being built for Python is not by itself a way into Python.

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
