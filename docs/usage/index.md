# Usage

The `jo` command is Jo's unified build tool. It handles compilation, testing, dependency management, and publishing — all from a single `jo.toml` build spec.

## Sections

- **[Concepts](concepts/projects.md)** — mental model: projects, packages, the registry
- **[Guides](guides/creating-a-project.md)** — task-oriented walkthroughs
- **[Reference](reference/build-spec.md)** — complete `jo.toml` field reference and version management
- **[Commands](commands/index.md)** — per-command documentation

## Quick Start

```sh
jo new my-app          # create a new app project
cd my-app
jo run                 # build and run
```

```sh
jo new my-lib --lib    # create a new library project
cd my-lib
jo test                # run tests
```
