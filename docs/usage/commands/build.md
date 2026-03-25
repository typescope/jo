# jo build

Build the project.

## Usage

```
jo build [--spec <file.toml>]
```

## Options

| Option          | Description                                        |
|-----------------|----------------------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`.             |

## What It Does

1. Reads the build spec and lock file
2. Fetches missing packages into `~/.jo/cache/`
3. Resolves dependencies
4. If the lock file exists, requires it to match the build exactly
5. If the lock file is missing, writes it from the resolved package set
6. Validates FFI compatibility across the dependency graph
7. Compiles the project

    - writes `.sast` files to `.build/<name>/jo-<version>/sast/`
    - For apps: emits output to `.build/<name>/jo-<version>/target/`

8. Merges foreign deps (`pip.txt`, `gems.txt`) into `.build/<name>/`

If the lock file is present but does not satisfy the current dependency constraints, `jo build` fails and asks you to run `jo lock`.

## Output

| Build kind | Output                                                                              |
|------------|-------------------------------------------------------------------------------------|
| Library    | `.build/<name>/jo-<version>/sast/`                                                  |
| App        | `.build/<name>/jo-<version>/sast/`, `.build/<name>/jo-<version>/target/`            |

## Examples

```sh
jo build
jo build --spec agent-api.toml
```
