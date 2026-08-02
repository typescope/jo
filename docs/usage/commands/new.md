# jo new

Create a new project with a standard scaffold, or from a third-party
template repo.

## Usage

```
jo new [--lib] <name>
jo new --template <ref> <name>
jo new --template <ref> --list
```

## Options

| Option        | Description                                             |
|---------------|----------------------------------------------------------|
| `--lib`       | Create a library project. Default: app. Not combinable with `--template`. |
| `--template`  | Scaffold from a template ref instead of the built-in app/lib scaffold. |
| `--list`      | List the templates declared by `--template`'s repo, instead of scaffolding. Requires `--template`, no `<name>`. |

## Template Scaffold

`jo new --template <ref> <name>` scaffolds from a third-party GitHub repo
instead of the built-in scaffolds. The repo must have a `jo-templates.jsonl`
manifest at its root declaring one or more named templates — this is what
makes a repo a valid Jo template repo.

Ref grammar: `[gh:]owner/repo[#gitref][:name]`. `gh:` is optional and
implied when omitted. `gitref` (branch, tag, or commit) defaults to the
repo's default branch. `name` selects a template when the repo declares
more than one.

```
jo new --template acme/jo-templates my-app              # single-template repo
jo new --template acme/jo-templates:web-app my-app      # multi-template repo, pick one
jo new --template acme/jo-templates#v2:web-app my-app   # pin a ref
jo new --template acme/jo-templates --list              # list available templates
```

There is no variable substitution, prompts, or setup hooks — files are
copied as-is.

### `jo-templates.jsonl`

One JSON object per line, at the repo root:

```jsonl
{"name": "web-app", "path": "templates/web-app", "description": "Minimal web app with a REST endpoint"}
{"name": "cli", "path": "templates/cli", "description": "CLI scaffold with arg parsing"}
```

| Field         | Required | Notes                                                                |
|---------------|----------|-----------------------------------------------------------------------|
| `name`        | yes      | Matches `[a-zA-Z0-9][a-zA-Z0-9_-]*`. Must be unique within the file.   |
| `path`        | yes      | Relative POSIX path from the repo root. No leading `/`, no `..` segments. Everything under it is copied verbatim into the new project. |
| `description` | no       | Free text, shown by `--list`. Not used for resolution.                |

A single-template repo still needs this file, just one line, typically:

```jsonl
{"name": "default", "path": ".", "description": "Minimal starter"}
```

With `path: "."`, the entire repo root — including `jo-templates.jsonl`
itself — gets copied into the scaffolded project, so keep any repo-only
files (CI config, contributor docs) in a subdirectory instead if you don't
want them showing up in generated projects.

A template is fetched via `git` when it's available on `PATH` (executable
bits and symlinks are preserved), falling back to a plain ZIP download
otherwise. As ZIP does not preserve executable bits nor symlinks,
`chmod +x` and symlink fixes might be necessary after scaffolding.

## App Scaffold

`jo new my-agent` prints:

```text
Created 'my-agent'

You can now:
  cd my-agent
  jo run
  jo run test
```

It creates:

```text
my-agent/
  jo.toml
  src/
  tests/
```

**App** (`my-agent/jo.toml`):

```toml
jo = "1.0"

[module.app]
kind = "app"
src = ["src/"]
platform = "python"

[module.test]
kind = "app"
src = ["tests/"]
platform = "python"

modules = ["app"]
```

## Library Scaffold

`jo new --lib my-lib` prints:

```text
Created 'my-lib'

You can now:
  cd my-lib
  jo build
  jo run test
```

It creates:

```text
my-lib/
  jo.toml
  src/
  tests/
```

**Library** (`my-lib/jo.toml`):

```toml
jo = "1.0"

[module.lib]
kind = "lib"
src = ["src/"]

[module.lib.package]
name = "my-lib"
version = "0.1.0"

[module.test]
kind = "app"
src = ["tests/"]
platform = "python"

modules = ["lib"]
```
