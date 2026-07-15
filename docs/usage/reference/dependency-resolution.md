# Dependency Resolution

Jo's current resolver is designed around three goals:

- **Simplicity**: one version per package, no backtracking solver
- **Predictability**: same available packages produce the same result
- **Explainability**: once Jo picks a version, that choice does not change later

The resolver works over published `.joy` packages. For each selected package, it reads
`meta.toml`, discovers that package's direct dependencies, and continues until the full
transitive graph is known.

Jo also resolves the compiler version before package selection:

- `jo.toml` declares a compatibility requirement such as `jo = "1.0"`
- `jo.lock` may pin an exact compiler version such as `jo = "1.2.0"`
- if `jo.lock` pins a version and it still satisfies `jo.toml`, Jo requires that exact version
- if the pinned version no longer satisfies `jo.toml`, Jo falls back to the running compiler
- the running compiler must satisfy `jo.toml` — if it does not, Jo errors and asks you to switch with `jo versions use`

Once the compiler is selected, package resolution only considers package versions whose
`meta.toml` `jo` requirement is satisfied by that compiler.

Yanked releases are ignored during version selection.

The root build spec may also provide `[pinning]` overrides. A pin such as
`mustache = "1.2.3"` means that Jo must use exactly that concrete version for
`mustache` if it is selected at all.

If no published package version fits the selected compiler, Jo reports the package's
required `jo` constraint so the reason is visible in the error message.

Local `path` dependencies are stricter: they are source projects in the same build, so
their `jo` requirements must also accept the selected compiler. A local project with an
incompatible `jo` requirement is an immediate error.

## How It Works

Jo resolves dependencies **level by level**.

You can think of this as a breadth-first walk over the dependency graph:

- start with the direct package dependencies of every source module in the build
- then move outward to the dependencies of those packages
- then continue outward level by level until the graph is complete

At each level, Jo keeps one simple rule: the first time it selects a package version,
that choice is fixed.

At each step:

1. Verify the running compiler satisfies `jo.toml` (using the pinned version from `jo.lock` if present and still compatible).
2. Start from the direct package dependencies of every source module in the build.
3. For each package, collect all version constraints discovered so far.
4. The first time a package is seen, look at its published versions.
5. Choose the **highest available non-yanked version** satisfying every package constraint known for that package at that moment.
6. If the root build spec pins that package, require the pinned exact version instead.
7. Ignore package versions whose `meta.toml` `jo` requirement is not satisfied by the selected compiler.
8. Fix that version choice.
9. Read that package's `meta.toml`.
10. Add its direct dependencies and continue in the same way.

This continues until the full transitive dependency graph is resolved.

After version selection is complete, the final package set is ordered so dependencies
come before dependents.

If no available version satisfies the package's known constraints and the selected compiler
when it is first selected,
resolution fails with an explicit conflict error.

If Jo later discovers a new constraint that does not match the already selected version,
resolution also fails. Jo does not backtrack and try a different version.

If a pinned exact version conflicts with dependency constraints, Jo also fails explicitly.

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

  <text x="104" y="166" text-anchor="middle" font-size="12" fill="currentColor">1.0</text>
  <text x="276" y="166" text-anchor="middle" font-size="12" fill="currentColor">1.2</text>

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

Jo uses one constraint form for both compiler and package requirements:

- `1.2`

That means:

- stay within major line `1`
- require at least `1.2.0`
- choose the highest available compatible release in that line

The provider supplies the set of available versions. The resolver then chooses the highest
version satisfying all collected constraints.

If `[pinning]` names a package, that exact concrete version is used instead of
“highest compatible version”. Pinning is root-only and never part of published package metadata.

## Multi-Module Projects

A build resolves as one universe. The lock file selects at most one version of each registry
package. The resolution roots are the direct package dependencies of *every* source module the
build reaches, not just the module you asked to build.

This means resolution always covers the whole build, even though module selection is per-command:

- `jo lock` resolves all modules and rewrites `jo.lock`.
- `jo build <module>`, `jo check`, `jo run`, and `jo doc` resolve all modules when `jo.lock` is
  missing, and write a complete lock file.
- once a valid `jo.lock` exists, build commands read it and do not re-resolve.

So a version conflict between two modules is reported whenever the lock is written, including by a
`jo build app` that has to create a missing lock, even if `app`'s own closure is clean. The
alternative would be a lock file whose contents depend on which module was built first, which is
not reproducible. Dependency depth is still checked for the selected module closure only. Use
`jo lock` to check depth across every module.

Source modules themselves are not part of this universe. They are source inputs and need no lock
entries. The registry packages they require do need entries. This holds however the module is
reached:

- a module in the same `jo.toml` contributes its package constraints to the resolution
- a module reached through `path` does too. Jo loads that project's `jo.toml` to find the module's
  sources and dependencies, then resolves those alongside the caller's own

An external project is not a resolution boundary. Source is source, wherever it is declared:
moving a module out of your `jo.toml` into a sibling directory does not change how its
dependencies resolve.

Two consequences follow:

- **The external project's `jo.lock` is ignored** while it is consumed as a source dependency.
  That lock governs standalone builds of that project. If it locks `mustache` at `1.5.0` and your
  build resolves `mustache` to `2.3.1`, your build uses `2.3.1` — the root build is authoritative
  for the build it is running.
- **There is one compiler for the whole source build.** An external project's `jo` requirement must
  accept the selected compiler, and it is an error if it does not.

Conflicts are reported across projects the same way they are across modules, naming both
requirement paths. Two source modules that need incompatible versions of the same package cannot
be part of one build. Putting them in separate directories does not help. A `path` edge still
pulls both into one resolution. They have to depend on the published package instead.

Runtime compatibility is checked per selected module closure. One project may hold pure, Python,
and Ruby modules at once, as long as each module's own closure agrees.

## Failure Cases

Resolution fails explicitly when:

- a package does not exist
- a package version exists in metadata but the `.joy` artifact is missing
- `meta.toml` is missing or malformed
- a local `path` dependency requires a different Jo compiler line
- constraints for a package are incompatible, including constraints declared by two different
  source modules, in the same project or reached through `path`
- source module dependencies form a cycle, across projects or within one
- package versions satisfy the dependency constraints but require a different Jo version
- published package metadata forms a cycle

## Dependency Depth

Dependency resolution and dependency depth are related but separate concerns.

- resolution selects package versions
- depth limits how deep the package dependency tree is allowed to become

`jo build`, `jo run`, `jo check`, `jo doc`, and `jo deps` enforce the effective `depth` for the
selected module closure. A module uses its own `depth`. If it declares none, the default is `0`
for `lib` modules and `1` for `app` modules. Depth is per module. There is no project-level
default.

A source module edge costs no depth. The registry packages reached through it do count toward the
depending module's depth. This holds whether that module is local or reached through `path`. See
[Dependency Depth](../concepts/dependency-depth.md).
