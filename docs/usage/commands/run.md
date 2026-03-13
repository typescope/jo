# jo run

Build and run the application.

## Usage

```
jo run [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## Examples

```sh
jo run
jo run --spec my-agent.toml
```

## Notes

Only valid for app builds. Running on a library spec is an error.
