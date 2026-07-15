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
  <text x="94"  y="168" text-anchor="middle" font-size="12" fill="currentColor">v1.1</text>
  <text x="226" y="168" text-anchor="middle" font-size="12" fill="currentColor">v1.2</text>

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

Jo makes package dependency depth **visible and intentional**. Each module declares its own `depth` when it needs more than the default for its kind. Adding registry package dependencies requires an explicit opt-in, recorded in the build spec and visible in code review.

Zero-dependency libraries are the default. They are easier to audit, more portable, and simpler to compose — properties that make them reliable building blocks for larger systems.

## How It Works

Only registry package dependencies count toward dependency depth. Source module dependencies are code you build, not artifacts you pull in, so the edge to one costs nothing.

The packages *behind* that edge still count. If your app depends on a source module that depends on `mustache`, that is a package your build acquired, and it counts toward your app's depth. This holds whether the source module sits in your own `jo.toml` or in another project reached through `path`. Otherwise `depth` would mean very little: any limit could be evaded by moving dependencies into a sibling directory.

For a module, the actual package depth is the longest path from that module through registry package dependencies, counting source module edges as zero. A direct dependency on a leaf package has depth 1. A package that depends on another package creates depth 2, and so on.

A module declares its maximum depth with `[module.<id>].depth`. If it declares none, it gets the built-in default for its kind:

| Module kind | Default `depth` |
|-------------|-----------------|
| `lib`       | `0` — no registry packages by default |
| `app`       | `1` — may depend directly on leaf packages |

Commands error if a module's actual package depth exceeds its limit.

Depth is per module, and there is no project-level default. A shared default would be read as "this project's budget" and would quietly raise every library in the project from `0` to `1` as soon as one app needed a deeper graph — the depth-0 library default is worth more than the saved line of TOML.

Tests are ordinary app modules. A test module uses the same rules as any other app module: set `[module.test].depth` if tests need a deeper package graph than the code under test.

`jo lock` resolves all modules and reports depth violations anywhere in the project. `jo build`, `jo check`, `jo run`, `jo doc`, and `jo deps` enforce depth for the selected module closure.

Higher depths are permitted by raising the value explicitly. The defaults ensure that any increase in dependency complexity is a deliberate decision.

## Examples

A depth-0 library — no registry dependencies:

```toml
jo = "1.0"

[module.mustache]
kind = "lib"

[module.mustache.package]
name = "mustache"
version = "1.0.0"
```

A depth-1 library — depends directly on a leaf package:

```toml
jo = "1.0"

[module.api]
kind = "lib"
depth = 1
packages = [{ name = "mustache", version = "1.0" }]

[module.api.package]
name = "agent-api"
version = "1.0.0"
```

An app using a package with one level of transitive package dependencies — raises its own depth to accommodate the full tree:

```toml
jo = "1.0"

[module.app]
kind = "app"
platform = "python"
depth = 2
packages = [{ name = "agent-api", version = "1.0" }]
```

Tests may use a different depth than the code they test:

```toml
jo = "1.0"
default = "app"

[module.core]
kind = "lib"

[module.app]
kind = "app"
platform = "python"
modules = ["core"]

[module.test]
kind = "app"
platform = "python"
src = ["tests"]
depth = 2
modules = ["core"]
packages = [{ name = "jo-test", version = "1.0" }]
```

Here `core` stays at the library default of `0` and the app at `1`, while tests are allowed to depend on a deeper package graph. Each limit is stated where it applies.

## Reducing Dependency Depth

Dependency depth is not only about counting packages — it also reflects how tightly components are coupled. Jo provides two complementary mechanisms that enable **dependency inversion**, allowing high-level packages to define contracts without depending on concrete implementations. This can eliminate entire dependency edges and keep libraries at lower depths.

### Deferred Functions and Linking

A library can declare extension points using `defer` without importing anything:

```jo
// agent-api/search.jo  (depth-0 library)
namespace AgentAPI

defer def embed(text: String): List[Float]
defer def vectorSearch(query: List[Float], topK: Int): List[String]

def findRelevant(question: String, k: Int): List[String] =
  val v = embed(question)
  vectorSearch(v, k)
```

The library defines the *shape* of its dependencies without taking a dependency on any embedding or search package. Concrete implementations are supplied at link time by the application:

```toml
[module.app]
kind = "app"
platform = "python"
modules = ["agent-api"]
links = [
  { from = "AgentAPI.embed", to = "OpenAI.embed" },
  { from = "AgentAPI.vectorSearch", to = "Pinecone.search" },
]
```

Because `agent-api` carries no imports, it remains depth-0. The concrete packages are wired in only at the application layer, where the extra depth is already expected. The dependency graph is inverted: the library no longer depends on its collaborators — the application does.

Linking is verified at compile time — type mismatches are errors, not surprises.

### Context Parameters

Context parameters let a library consume services (loggers, connections, finders) without importing their implementations. The library declares a typed parameter. Callers supply the value:

```jo
// report-lib/report.jo  (depth-0 library)
namespace Report

interface Renderer
  def render(doc: String): String
end

param renderer: Renderer

def generate(title: String, body: String): String =
  renderer.render("# " + title + "\n\n" + body)
```

The library compiles without knowing about `HtmlRenderer`, `MarkdownRenderer`, or any third-party package. The application provides the binding:

```jo
// app.jo
import reportLib.Report
import htmlLib.HtmlRenderer

def main =
  val r = new HtmlRenderer
  println(with renderer = r in Report.generate("Hello", "World"))
```

The static type system tracks every context parameter across the full call chain. If a binding is missing the compiler reports an error with a precise trace — so the zero-dependency library stays safe without any runtime framework.

Together, deferred functions and context parameters let a library express *what it needs* without hardcoding *where it comes from*. The result: shallower libraries that are easier to audit, compose, and reuse.
