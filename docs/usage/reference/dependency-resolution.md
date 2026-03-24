# Dependency Resolution

Jo's current resolver is designed around three goals:

- **Simplicity**: one version per package, no backtracking solver
- **Predictability**: same available packages produce the same result
- **Explainability**: once Jo picks a version, that choice does not change later

The resolver works over published `.joy` packages. For each selected package, it reads
`meta.toml`, discovers that package's direct dependencies, and continues until the full
transitive graph is known.

## How It Works

Jo resolves dependencies **level by level**.

You can think of this as a breadth-first walk over the dependency graph:

- start with the app's direct dependencies
- then move outward to the dependencies of those packages
- then continue outward level by level until the graph is complete

At each level, Jo keeps one simple rule: the first time it selects a package version,
that choice is fixed.

At each step:

1. Start from the app's direct package dependencies.
2. For each package, collect all version constraints discovered so far.
3. The first time a package is seen, look at its published versions.
4. Choose the **highest available version** satisfying every constraint known for that package at that moment.
5. Fix that version choice.
6. Read that package's `meta.toml`.
7. Add its direct dependencies and continue in the same way.

This continues until the full transitive dependency graph is resolved.

After version selection is complete, the final package set is ordered so dependencies
come before dependents.

If no available version satisfies the package's known constraints when it is first selected,
resolution fails with an explicit conflict error.

If Jo later discovers a new constraint that does not match the already selected version,
resolution also fails. Jo does not backtrack and try a different version.

## Illustration

In this example, the app depends on `A` and `B`. Both introduce constraints on `Core`.
Jo collects both constraints and then selects the highest published `Core` version
compatible with both.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 280" width="420" height="280" style="display:block;margin:0 auto;font-family:inherit">
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
  <text x="190" y="252" text-anchor="middle" font-size="11" fill="#14532d">fix that choice</text>
</svg>

## Why This Algorithm

This approach is a good fit for Jo because it is easy to understand:

- each package ends up with exactly one selected version
- version choice depends only on published versions and the constraints known when the package is first selected
- once selected, a version never changes later in the same resolution run
- higher compatible versions are preferred automatically
- no SAT solving or backtracking is needed
- the same dependency graph always resolves the same way against the same published versions

It also matches the package format naturally:

- the resolver reads real dependency metadata from `meta.toml`
- the same logic works with a local test provider and with future registry-backed providers
- tests can exercise the algorithm entirely offline using real `.joy` fixtures

This is intentionally not a backtracking solver. If a later package introduces a new
incompatible requirement, Jo reports that conflict instead of trying to revise earlier
choices. That keeps the algorithm easy to predict and easy to explain.

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
