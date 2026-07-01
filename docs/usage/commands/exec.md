# jo &lt;name&gt; / jo exec

Run a project-defined command from the [`[commands]`](../reference/build-spec.md#commands-project-commands) table in `jo.toml`.

## Usage

```
jo <name> [args...]        # built-in first, then a [commands] entry
jo exec <name> [args...]   # always a [commands] entry, bypassing built-ins
```

## How It Works

Commands are named shell aliases declared in `[commands]`:

```toml
[commands]
dev = "jo build --spec sandbox/guest/jo.toml && jo run"
fmt = "echo formatting..."
```

- **`jo <name>`** resolves built-in commands first (`build`, `run`, `test`, …), then falls back to a `[commands]` entry. Built-ins can never be shadowed by a project, so `jo build` is always the real build even if a `build` command is defined.
- **`jo exec <name>`** skips built-ins and runs the `[commands]` entry directly. Use it when a command shares a name with a built-in, or to stay stable if a future Jo version adds a built-in of the same name.

::: info Why built-ins take precedence — a deliberate design choice
`jo <name>` resolves built-ins **before** project commands on purpose, so a `jo.toml` can never redefine `jo build`, `jo run`, or `jo test`. This keeps those commands safe to type in any directory: cloning an untrusted repository and running `jo build` will never execute shell defined in its manifest.

The trade-off is intentional. Letting project commands override built-ins would be convenient, but to be useful an override must fire automatically whenever anyone types the verb — which is exactly what would turn `git clone && jo build` into arbitrary code execution.

Consequently, commands run **only when you invoke them by name** — there are no install or lifecycle hooks that run on their own. To customize what a built-in does for a project, define a distinctly-named command (e.g. `dev`) and run `jo dev`, instead of shadowing the built-in.
:::

Each command runs through `sh -c` from the project root, so shell features like `&&` and pipes work. Extra arguments are appended to the command line, and the command's exit status becomes `jo`'s exit status.

```sh
jo dev              # runs: jo build --spec sandbox/guest/jo.toml && jo run
jo dev --port 8080  # appends `--port 8080`
jo exec build       # runs the [commands] "build", not the built-in
```

## Errors

If neither a built-in nor a defined command matches, `jo` reports it and lists the available commands:

```
$ jo bogus
Error: unknown command 'bogus'
Defined commands: dev, fmt
```

## Examples

```sh
jo dev
jo fmt
jo exec dev
```

## See Also

- [`[commands]`](../reference/build-spec.md#commands-project-commands) — declaring commands in `jo.toml`.
