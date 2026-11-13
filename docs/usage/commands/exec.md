# jo exec

Run a project-defined command from the [`[commands]`](../reference/build-spec.md#commands) table in `jo.toml`.

## Usage

```
jo exec <name> [args...]
```

## How It Works

Commands are named shell aliases declared in `[commands]`:

```toml
[commands]
dev = "jo build --spec sandbox/guest/jo.toml && jo run"
test = "jo run test"
```

`jo exec` is the only way to run them. A built-in command such as `jo build` is always the built-in, whatever `jo.toml` defines, and `jo <name>` never runs a project command. For a name that is not a built-in it reports an unknown command and, if the project defines that name, points at `jo exec`. Built-in and project commands never share a namespace, so a project command keeps working when a later Jo version adds a built-in of the same name.

Nothing runs a project command on its own. Cloning a repository and running `jo build` never executes shell defined in its manifest, and there are no install or lifecycle hooks.

Each command runs through `sh -c` from the project root, so shell features like `&&` and pipes work. Extra arguments are appended to the command line as literal arguments, and the command's exit status becomes `jo`'s exit status.

```sh
jo exec dev              # runs: jo build --spec sandbox/guest/jo.toml && jo run
jo exec dev --port 8080  # appends `--port 8080`
jo exec test             # runs: jo run test
```

## Errors

If no defined command matches, `jo exec` reports it and lists the available commands:

```
$ jo exec bogus
Error: unknown command 'bogus'
Defined commands: dev, test
```

## See Also

- [`[commands]`](../reference/build-spec.md#commands) — declaring commands in `jo.toml`.
