# jo versions

Manage installed compiler versions.

## Usage

```
jo versions list
jo versions install <version>
jo versions use <version>
jo versions remove <version>
```

## Subcommands

### `jo versions list`

List all installed compiler versions. The active version is marked.

```sh
jo versions list
```

When fewer than 10 versions are installed, each is listed individually:

```
Installed compiler versions:

  0.10.0 (active)
  0.9.0
```

When 10 or more versions are installed, they are grouped by minor version with patch ranges shown compactly:

```
Installed compiler versions:

  0.11.{0-3}
  0.10.{0-5} (active)
  0.9.{0}
```

### `jo versions install <version>`

Download and install a specific compiler version from the Jo release index at `jo-lang.org/versions.jsonl`.

```sh
jo versions install 0.10.0
```

### `jo versions use <version>`

Switch the active compiler by rewriting the launcher at `~/.local/bin/jo` to point to the specified version. The version must already be installed.

```sh
jo versions use 0.9.0
```

### `jo versions remove <version>`

Remove an installed compiler version.

```sh
jo versions remove 0.9.0
```

## Compiler Layout

Each version is installed under `~/.jo/compilers/<version>/`:

```
~/.jo/compilers/0.10.0/
  jo.jar
  bin/
    jo
  libs/
  assets/
  LICENSE
```

The active launcher at `~/.local/bin/jo` delegates to the selected version's `bin/jo`.

## See Also

- [Install](../install.md) — initial installation
