# jo search

Search the package registry.

## Usage

```
jo search <query>
```

## Examples

```sh
jo search agent
jo search "http client"
```

## Notes

Searches package names, namespaces, and keywords. Uses the local index snapshot (`~/.jo/index.db`). If results seem stale, run `jo build` in any project to refresh the index.
