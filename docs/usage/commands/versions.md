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

Show installed and available compiler versions.

```sh
jo versions list
```

Installed versions are shown first with the active one marked. Available versions
not yet installed are shown in a second section.

When fewer than 10 versions appear in a section, they are listed individually:

```
Installed:

  0.10.0 (active)

Available:

  0.10.1
  0.9.0
```

When 10 or more versions appear in a section, they are grouped by minor version
with patch ranges shown compactly:

```
Installed:

  0.11.{0-3}
  0.10.{0-5} (active)
  0.9.{0-1}
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
