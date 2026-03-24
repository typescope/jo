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

1. Reads the build spec, collects dependencies and optionally updates the lock file via resolution
2. Fetches missing packages into `~/.jo/cache/`
3. Validates FFI compatibility across the dependency graph
4. Compiles the project

    - writes `.sast` files to `.build/<name>/jo-<version>/sast/`
    - For apps: emits output to `.build/<name>/jo-<version>/target/`

5. Merges foreign deps (`pip.txt`, `gems.txt`) into `.build/<name>/`

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
