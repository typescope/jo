# Dependency Resolution

Jo's current resolver is designed around three goals:

- **Simplicity**: one version per package, no backtracking solver
- **Predictability**: same available packages produce the same result
- **Explainability**: the selected version must satisfy every collected constraint

The resolver works over published `.joy` packages. For each selected package, it reads
`meta.toml`, discovers that package's direct dependencies, and continues until the full
transitive graph is known.

## How It Works

Jo resolves dependencies **level by level**.

At each step:

1. Start from the app's direct package dependencies.
2. For each package, collect all version constraints discovered so far.
3. Look at the published versions available for that package.
4. Choose the **highest available version** satisfying every collected constraint.
5. Read that package's `meta.toml`.
6. Add its direct dependencies and continue in the same way.

This continues until the full transitive dependency graph is resolved.

After version selection is complete, the final package set is ordered so dependencies
come before dependents.

If no available version satisfies all collected constraints for a package, resolution
fails with an explicit conflict error.

## Illustration

In this example, the app depends on `A` and `B`. Both introduce constraints on `Core`.
Jo collects both constraints and then selects the highest published `Core` version
compatible with both.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 380 250" width="380" height="250" style="display:block;margin:0 auto;font-family:inherit">
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <polygon points="0 0,8 3,0 6" fill="currentColor"/>
    </marker>
  </defs>

  <g stroke="currentColor" stroke-width="1.5" fill="none" marker-end="url(#arr)">
    <line x1="188" y1="48" x2="112" y2="104"/>
    <line x1="192" y1="48" x2="268" y2="104"/>
    <line x1="112" y1="134" x2="176" y2="190"/>
    <line x1="268" y1="134" x2="204" y2="190"/>
  </g>

  <text x="104" y="166" text-anchor="middle" font-size="12" fill="currentColor">^1.0</text>
  <text x="276" y="166" text-anchor="middle" font-size="12" fill="currentColor">~1.2</text>

  <rect x="155" y="18" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="190" y="37" text-anchor="middle" font-size="13" fill="currentColor">App</text>

  <rect x="75" y="104" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="110" y="123" text-anchor="middle" font-size="13" fill="currentColor">A</text>

  <rect x="235" y="104" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="270" y="123" text-anchor="middle" font-size="13" fill="currentColor">B</text>

  <rect x="135" y="186" width="110" height="30" rx="5" fill="#bbf7d0" stroke="#15803d" stroke-width="1.5"/>
  <text x="190" y="205" text-anchor="middle" font-size="13" fill="#14532d">Core</text>
  <text x="190" y="232" text-anchor="middle" font-size="11" fill="#14532d">pick highest compatible</text>
</svg>

## Why This Algorithm

This approach is a good fit for Jo because it is easy to understand:

- each package ends up with exactly one selected version
- version choice depends only on published versions and collected constraints
- higher compatible versions are preferred automatically
- no SAT solving or backtracking is needed
- the same dependency graph always resolves the same way against the same published versions

It also matches the package format naturally:

- the resolver reads real dependency metadata from `meta.toml`
- the same logic works with a local test provider and with future registry-backed providers
- tests can exercise the algorithm entirely offline using real `.joy` fixtures

## Constraint Semantics

The current resolver supports:

- caret ranges: `^1.2`
- tilde ranges: `~1.2`
- explicit comparisons: `>=1.0`, `<2.0`, `=1.2.3`
- comma-separated conjunctions: `>=1.0, <2.0`

The provider supplies the set of available versions. The resolver then chooses the highest
version satisfying all collected constraints.

## Failure Cases

Resolution fails explicitly when:

- a package does not exist
- a package version exists in metadata but the `.joy` artifact is missing
- `meta.toml` is missing or malformed
- constraints for a package are incompatible
- published package metadata forms a cycle

## Dependency Depth

Dependency resolution and dependency depth are related but separate concerns.

- resolution selects package versions
- depth limits how deep a dependency tree is allowed to become

`jo build` also enforces the `depth` constraint declared in the build spec. See
[Dependency Depth](../concepts/dependency-depth.md).
