# Install

::: info
Jo currently supports **Linux** and **macOS** only. Windows is not supported.
:::

```sh
curl -sSf https://jo-lang.org/install.sh | sh
```

The installer downloads the compiler to `~/.jo/compilers/<version>/` and creates a
launcher script at `~/.local/bin/jo`. Add `~/.local/bin` to your `PATH` if it is not
already there:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

Verify the installation:

```sh
jo --version
```

## Pro tip: native launcher for faster startup

The default launcher runs `jo.jar` on the JVM, paying a cold-start cost on every call.
Compiling the jar to a native binary makes startup near-instant.

Install [GraalVM](https://www.graalvm.org/downloads/) (its JDK ships `native-image`)
and confirm `native-image --version` works. Then compile the installed jar and point
the launcher at the result:

```sh
cd "$HOME/.jo/compilers/$(jo --version)/bin"
native-image --no-fallback -jar jo.jar -o jo.native
```

Then change the last line of `jo` to launch the native binary:

```sh
exec "$BIN_DIR/jo.native" "$@"
```

## Troubleshooting

**`autojump: directory '...' not found`** — autojump defines its own `jo` command (open file manager) that shadows the compiler. Add this to your shell config to override it:

::: code-group
```sh [fish (~/.config/fish/config.fish)]
function jo
    command jo $argv
end
```
```sh [bash/zsh (~/.bashrc or ~/.zshrc)]
alias jo='command jo'
```
:::

**`env: python: No such file or directory`** — also from autojump; resolved by the fix above.
