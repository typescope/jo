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
name    = "mustache"
# depth = 0 (default)

[package]
version = "1.0.0"
```

A depth-1 library — depends on depth-0 libraries:

```toml
name    = "agent-api"
depth   = 1

[package]
version = "1.0.0"
```

An app using a depth-1 library — raises its own depth to accommodate the full tree:

```toml
name   = "my-app"
depth  = 2

[main]
target = "python"
```

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

```bash
bin/jo build app.jo \
  -link AgentAPI.embed=OpenAI.embed \
  -link AgentAPI.vectorSearch=Pinecone.search \
  -o app
```

Because `agent-api` carries no imports, it remains depth-0. The concrete packages (`openai`, `pinecone`) are wired in only at the application layer, where the extra depth is already expected. The dependency graph is inverted: the library no longer depends on its collaborators — the application does.

Linking is verified at compile time — type mismatches are errors, not surprises.

### Context Parameters

Context parameters let a library consume services (loggers, connections, finders) without importing their implementations. The library declares a typed parameter; callers supply the value:

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
  println(Report.generate("Hello", "World") with renderer = r)
```

The static type system tracks every context parameter across the full call chain. If a binding is missing the compiler reports an error with a precise trace — so the zero-dependency library stays safe without any runtime framework.

Together, deferred functions and context parameters let a library express *what it needs* without hardcoding *where it comes from*. The result: shallower libraries that are easier to audit, compose, and reuse.
