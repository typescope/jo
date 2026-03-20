# Dependency Depth

## Problem

Deep dependency trees are a common source of [dependency hell](https://en.wikipedia.org/wiki/Dependency_hell) — a condition where resolving version conflicts across transitive dependencies becomes a significant maintenance burden.

A well-known manifestation is the **diamond dependency problem**: when two libraries B and C both depend on library D but require incompatible versions, the resolver must select one version — potentially breaking either B or C. The deeper the dependency tree, the higher the probability of such conflicts, and the more difficult they are to trace.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 230" width="320" height="230" style="display:block;margin:0 auto;font-family:inherit">
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <polygon points="0 0,8 3,0 6" fill="currentColor"/>
    </marker>
  </defs>

  <!-- Edges -->
  <g stroke="currentColor" stroke-width="1.5" fill="none" marker-end="url(#arr)">
    <line x1="148" y1="50" x2="92"  y2="105"/>
    <line x1="172" y1="50" x2="228" y2="105"/>
    <line x1="92"  y1="135" x2="148" y2="185"/>
    <line x1="228" y1="135" x2="172" y2="185"/>
  </g>

  <!-- Edge labels -->
  <text x="104" y="157" text-anchor="middle" font-size="12" fill="currentColor">v1.1</text>
  <text x="216" y="157" text-anchor="middle" font-size="12" fill="currentColor">v1.2</text>

  <!-- App -->
  <rect x="125" y="20" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="160" y="39" text-anchor="middle" font-size="13" fill="currentColor">App</text>

  <!-- B -->
  <rect x="45" y="105" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="80" y="124" text-anchor="middle" font-size="13" fill="currentColor">B</text>

  <!-- C -->
  <rect x="205" y="105" width="70" height="30" rx="5" fill="none" stroke="currentColor" stroke-width="1.5"/>
  <text x="240" y="124" text-anchor="middle" font-size="13" fill="currentColor">C</text>

  <!-- D (conflict) -->
  <rect x="125" y="185" width="70" height="30" rx="5" fill="#fca5a5" stroke="#dc2626" stroke-width="1.5"/>
  <text x="160" y="204" text-anchor="middle" font-size="13" fill="#991b1b">D</text>
</svg>

Beyond version conflicts, deep trees introduce:

- **Security exposure** — vulnerabilities in packages not explicitly selected as dependencies
- **Reduced auditability** — transitive dependencies are difficult to review and verify
- **Cascading failures** — removal or breakage of a transitive dependency can affect unrelated dependents

## Solution

Jo makes dependency depth **visible and intentional**. Every package declares the maximum depth it allows. Adding dependencies requires an explicit opt-in, recorded in the build spec and visible in code review.

Zero-dependency libraries are the default. They are easier to audit, more portable, and simpler to compose — properties that make them reliable building blocks for larger systems.

## How It Works

The depth of a package is the maximum depth of its dependencies plus one. Leaf packages (no dependencies) have depth 0.

Each build spec declares a `depth` — the maximum allowed depth among its dependencies. `jo build` errors if any dependency's actual depth exceeds the declared value.

| Build kind | Default `depth` |
|------------|-----------------|
| Library    | `0` — no dependencies by default |
| App        | `1` — may depend on depth-0 libraries |

Higher depths are permitted by raising the value explicitly. The defaults ensure that any increase in dependency complexity is a deliberate decision.

## Examples

A depth-0 library — no dependencies:

```toml
[package]
name    = "mustache"
version = "1.0.0"
# depth = 0 (default)
```

A depth-1 library — depends on depth-0 libraries:

```toml
[package]
name    = "agent-api"
version = "1.0.0"
depth   = 1
```

An app using a depth-1 library — raises its own depth to accommodate the full tree:

```toml
[main]
target = "python"
depth  = 2
```
