# jo info

Show package metadata and available versions.

## Usage

```
jo info <pkg>[@<version>]
```

## Examples

```sh
jo info agent-api
jo info agent-api@1.2.0
```

## Output

It shows:

- the selected version
- all available versions
- library metadata from `meta.toml`
- direct package dependencies

If no version is given, `jo info` shows the latest available version.
