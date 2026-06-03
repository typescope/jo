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
