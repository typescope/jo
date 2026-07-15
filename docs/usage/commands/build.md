# jo build

Build a module.

## Usage

```
jo build [module]
```

If `module` is omitted, Jo builds the project default module.

## What It Does

1. Reads the build spec
2. Loads `jo.lock` if it exists. If it does not, Jo resolves all modules in the project and creates it
3. Fetches missing packages into `~/.jo/cache/`
4. Validates runtime compatibility across the dependency graph
5. Compiles the selected module

    - writes `.sast` files to `.build/<module>/jo-<version>/sast/`
    - For apps: emits output to `.build/<module>/jo-<version>/target/`

If `jo.lock` exists, `jo build` uses it as-is. Run `jo lock` manually after changing package dependencies.

## Output

| Build kind | Output                                                                              |
|------------|-------------------------------------------------------------------------------------|
| Library    | `.build/<module>/jo-<version>/sast/`                                                |
| App        | `.build/<module>/jo-<version>/sast/`, `.build/<module>/jo-<version>/target/`        |

## Examples

```sh
jo build
jo build api
```
