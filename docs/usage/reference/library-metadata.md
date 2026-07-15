# Library Metadata

Each `.joy` archive contains a `meta.toml` file with metadata about the package.
`jo package <module>` derives it from `[module.<id>.package]` and the packaged
module's compiled output.

::: info
Library metadata (`meta.toml`) and build spec (`jo.toml`) serve different
purposes: the build spec is instructions for *producing* an artifact.
`meta.toml` describes the *produced* artifact for consumers.
:::

## Fields

| Field          | Type            | Description |
|----------------|-----------------|-------------|
| `name`         | string          | Package name from `[module.<id>.package].name`. |
| `jo`           | string          | Required Jo compatibility line from top-level `jo`. |
| `version`      | string          | Package version from `[module.<id>.package].version`. |
| `namespace`    | string          | Namespace declared by the packaged sources. |
| `runtime`      | string          | Required runtime: `"pure"`, `"python"`, or `"ruby"`. |
| `description`  | string          | Optional package description. |
| `authors`      | array of string | Optional authors. |
| `license`      | string          | Optional license. |
| `homepage`     | string          | Optional homepage. |
| `keywords`     | array of string | Optional keywords. |
| `dependencies` | table           | Direct registry dependencies plus direct publishable source module dependencies. Link dependencies are omitted. |

## The `runtime` Field

`runtime` is always present in generated `meta.toml`. It is one of `"pure"`,
`"python"`, or `"ruby"`.

The author declares it in `[module.<id>.package].runtime`. If omitted, Jo records
`"pure"`. A runtime package may be published, but published package dependencies
must still be pure registry packages.

## Namespace-to-Directory Mapping

Dots in the namespace become directory separators. The compiler locates `.sast`
files under the path derived from the declared namespace:

```
namespace = "agentapi"       ->  agentapi/
namespace = "parsing.lexer"  ->  parsing/lexer/
```

The release archive contains the packaged module's own compiled `.sast` files
under that namespace root.

## Examples

### Example `meta.toml`

```toml
namespace = "agentapi"
name = "agent-api"
jo = "1.0"
version = "1.0.0"
runtime = "pure"
description = "Sandbox agent framework API"
authors = ["Alice <alice@example.com>"]
license = "MIT"
homepage = "https://github.com/alice/agent-api"
keywords = ["agent", "framework"]

[dependencies]
mustache = "1.0"
```

### Example Archive Layout

```
agent-api-v1.2.0.joy
  meta.toml          # package metadata
  agentapi/
    AgentAPI.sast    # one .sast per source file
    QueryDSL.sast
```

`.sast` files are target-independent in format, so the same `.joy` can serve
multiple backends.
