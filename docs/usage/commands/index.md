# Commands

| Command | Description |
|---------|-------------|
| [`jo new`](new.md) | Create a new project with scaffold |
| [`jo build`](build.md) | Build a module |
| [`jo run`](run.md) | Build and run an app module |
| [`jo check`](check.md) | Type-check only, no code generation |
| [`jo doc`](doc.md) | Generate module API documentation |
| [`jo package`](package.md) | Build a distributable module package |
| [`jo info`](info.md) | Show package metadata and versions |
| [`jo deps`](deps.md) | Show dependencies |
| [`jo lock`](lock.md) | Resolve dependencies and write the lock file |
| [`jo clean`](clean.md) | Remove build artifacts |
| [`jo versions`](versions.md) | Manage compiler versions |
| [`jo compile`](compile.md) | Raw compiler interface |
| [`jo <name>` / `jo exec`](exec.md) | Run a project command from `[commands]` |

## Global Options

| Option          | Description                                    |
|-----------------|------------------------------------------------|
| `--spec <file>` | Build spec to act on. Default: `jo.toml` in the current directory. Use it to run a command against a project in another directory, for example `jo build --spec ../agent-api/jo.toml`. |
| `--verbose`     | Enable verbose logging (build progress, resolution details) |
