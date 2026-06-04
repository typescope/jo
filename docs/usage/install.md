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
