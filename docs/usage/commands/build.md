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

    - writes `.sast` files to `.build/<stem>/sast/`
    - For apps: emits output to `.build/<stem>/target/`

5. Merges foreign deps (`pip.txt`, `gems.txt`) into `.build/<stem>/`

## Output

| Build kind | Output                                                    |
|------------|-----------------------------------------------------------|
| Library    | `.build/<stem>/sast/`                                     |
| App        | `.build/<stem>/sast/`, `.build/<stem>/target/`            |

## Examples

```sh
jo build
jo build --spec agent-api.toml
```
