# jo clean

Remove build artifacts.

## Usage

```
jo clean [--spec <file.toml>]
```

## Options

| Option          | Description                                              |
|-----------------|----------------------------------------------------------|
| `--spec <file>` | Remove `.build/<stem>/` for this spec. Default: `jo.toml`. If omitted entirely, removes all of `.build/`. |

## Examples

```sh
jo clean                        # remove .build/jo/
jo clean --spec agent-api.toml  # remove .build/agent-api/
jo clean                        # remove all of .build/ when no jo.toml present
```
