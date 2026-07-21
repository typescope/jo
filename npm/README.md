# @typescope/jo

npm installer and launcher for the Jo compiler.

```sh
npm install -g @typescope/jo
jo --version
```

The package version matches the Jo compiler version. During `postinstall`, the
package downloads the matching Jo release from `jo-lang.org/versions.jsonl`,
verifies its SHA256 checksum, and installs it under:

```text
~/.jo/compilers/<version>/
```

npm owns the `jo` command shim. The shim delegates to the installed compiler at
`~/.jo/compilers/<version>/bin/jo`; it does not write `~/.local/bin/jo`.

Jo currently supports Linux and macOS. Java and `tar` must be available on
`PATH`.
