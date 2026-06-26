# jo versions

Manage installed compiler versions.

## Usage

```
jo versions
jo versions install <version>
jo versions use <version>
jo versions remove <version>
```

`jo versions` with no subcommand lists installed and available versions.

## Subcommands

### `jo versions` / `jo versions list`

Show installed and available compiler versions.

Installed versions are always listed individually so the active version is
unambiguous. All available versions are shown in a second section regardless
of whether they are installed, grouped by minor version when there are 10 or more:

```
Installed:

  0.10.1
  0.10.0 (active)

Available:

  0.11.{0-3}
  0.10.{0-5}
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
~/.jo/compilers/0.11.1/
  bin/
    jo
    jo.jar
  libs/
  assets/
  LICENSE
```

The launcher `bin/jo` sets `JO_HOME` to the version directory and runs the sibling
`bin/jo.jar`. The active launcher at `~/.local/bin/jo` delegates to the selected
version's `bin/jo`.

## See Also

- [Install](../install.md) — initial installation
