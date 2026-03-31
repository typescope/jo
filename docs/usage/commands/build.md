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
4. Reuses compatible lock entries and refreshes missing ones
5. Fails on incompatible locked versions or digest mismatches
6. Validates runtime compatibility across the dependency graph
7. Compiles the project

    - writes `.sast` files to `.build/<name>/jo-<version>/sast/`
    - For apps: emits output to `.build/<name>/jo-<version>/target/`

`jo build` updates the lock automatically when it needs to add compatible newly resolved entries.
It fails only when existing locked entries are incompatible with the current build.

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
