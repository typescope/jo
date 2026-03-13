# Compiler Versions

Jo manages multiple compiler versions automatically. The `jo` constraint in `jo.toml` determines which version is used for each project.

## Version Switching

When you run any `jo` command, the compiler checks the `jo` constraint in the build spec:

- **Required version already installed** — switches with a note:
  ```
  note: using jo 1.0.0 as required by jo.toml
  ```

- **Required version not installed** — prompts before downloading:
  ```
  note: this project requires jo 1.0.0 (current: 0.9.0); not installed
  download jo 1.0.0? [y/N]
  ```

## Managing Versions

```sh
jo versions install <version>    # download and cache (prompts for confirmation)
jo versions list                 # list installed versions
jo versions remove <version>     # remove a cached version
```

## Pinning a Session

Set `JO_VERSION` in your environment to use a specific version regardless of the build spec:

```sh
export JO_VERSION=1.0.0
jo build    # always uses 1.0.0
```

Useful when working across multiple projects with different constraints in one shell session.

## Compiler Cache Layout

Each version is cached under `~/.jo/cache/compilers/<version>/`:

```
~/.jo/cache/compilers/1.0.0/
  bin/
    jo                   # compiler + build tool
  libs/
    core.joy             # standard library
    runtime/
      python.joy         # Python platform runtime
      js.joy             # JS platform runtime
      ruby.joy           # Ruby platform runtime
      native.joy         # native platform runtime
  docs/
    language/
  LICENSE
  CHANGELOG.md
```

The stdlib and platform runtimes ship as `.joy` packages inside the compiler distribution. They are loaded directly by the compiler without going through the registry.
