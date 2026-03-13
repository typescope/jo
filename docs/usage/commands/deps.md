# jo deps

Show project dependencies.

## Usage

```
jo deps [--spec <file.toml>] [--pip | --gems]
```

## Options

| Option          | Description                                           |
|-----------------|-------------------------------------------------------|
| `--spec <file>` | Build spec to use. Default: `jo.toml`.                |
| `--pip`         | Show merged Python foreign deps instead of Jo deps.   |
| `--gems`        | Show merged Ruby foreign deps instead of Jo deps.     |

## Examples

```sh
jo deps              # resolved Jo dependency tree
jo deps --pip        # merged pip.txt content (all transitive Python deps)
jo deps --gems       # merged gems.txt content
```

## Notes

`--pip` and `--gems` print the same content as `.build/<stem>/pip.txt` etc. Useful for piping into package installers:

```sh
jo deps --pip | pip install -r /dev/stdin
```
