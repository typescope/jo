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

Shows package metadata (description, authors, license, homepage) and a list of available versions with their `sastVersion` compatibility. Useful for checking which versions are compatible with the current compiler, or for diagnosing why a version is unavailable.
