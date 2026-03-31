# jo clean

Remove build artifacts for the current project.

## Usage

```
jo clean [--spec <file.toml>]
```

## Behaviour

Deletes `.build/<name>/` in the project directory, where `<name>` is the project name from the spec file. This includes compiled `.sast` files, target executables, and the `.done` sentinels used for incremental builds.

**Only the current project is cleaned.** Path dependencies have their own `.build/` directories and must be cleaned separately by running `jo clean` in each dependency's directory. The global package cache (`~/.jo/cache/packages/`) is never affected.

## Options

| Option          | Description                                        |
|-----------------|----------------------------------------------------|
| `--spec <file>` | Spec file to read the project name from. Default: `jo.toml`. |

## Examples

```sh
jo clean                        # clean .build/my-app/ for the default jo.toml
jo clean --spec agent-api.toml  # clean .build/agent-api/
```
