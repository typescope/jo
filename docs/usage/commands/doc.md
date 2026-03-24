# jo doc

Generate API documentation.

## Usage

```
jo doc [--spec <file.toml>]
```

## Options

| Option          | Description                            |
|-----------------|----------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`. |

## Output

Generated HTML files are written to `.build/<name>/doc/`.

## Examples

```sh
jo doc
jo doc --spec agent-api.toml
```
