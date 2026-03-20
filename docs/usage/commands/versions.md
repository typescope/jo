# jo versions

Manage installed compiler versions.

## Usage

```
jo versions install <version>
jo versions list
jo versions remove <version>
```

## Subcommands

### `jo versions install <version>`

Download and cache a compiler version. Prompts for confirmation before downloading.

```sh
jo versions install 1.0.0
```

### `jo versions list`

List all installed compiler versions.

```sh
jo versions list
```

### `jo versions remove <version>`

Remove a cached compiler version.

```sh
jo versions remove 0.9.0
```

## Notes

Installed versions are cached under `~/.jo/cache/compilers/<version>/`.

Version switching happens automatically when you run any `jo` command — the compiler reads the `jo` constraint from `jo.toml` and switches to the appropriate cached version. See [Compiler Versions](../reference/versions.md) for details.
