# jo check

Type-check source files without generating any code.

## Usage

```
jo check [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## Examples

```sh
jo check
jo check --spec agent-api.toml
```

## Notes

Faster than `jo build` — no backend invocation. Useful for editor integration and CI feedback loops where you only need type errors, not compiled output.
