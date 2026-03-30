# jo run

Run the application — first build if needed.

## Usage

```
jo run [--spec <file.toml>]
```

Only valid for app builds. Running on a library spec is an error.

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## What It Does

`jo run` rebuilds the app if sources or dependencies have changed, then runs the generated script using the target runtime.

`jo run` reuses compatible lock entries and may refresh missing ones automatically. It fails
when an existing locked entry is incompatible with the current build.
## Examples

```sh
jo run
jo run --spec my-agent.toml
```
