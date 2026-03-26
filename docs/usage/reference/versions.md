# Compiler Versions

The `jo` constraint in `jo.toml` determines which compiler version a project requires.

## Version Switching

When you run a project command, Jo checks the `jo` constraint in the build spec and selects an installed compiler that satisfies it.

- **Required version already installed** — switches with a note:
  ```
  note: using jo 1.0.0 as required by jo.toml
  ```

- **Required version not installed** — fails and asks you to install one:
  ```
  note: this project requires jo 1.0.0 (current: 0.9.0); not installed
  install jo 1.0.0 under ~/.jo/compiler/1.0.0/
  ```

## Installing Versions

Jo does not define a built-in compiler installer workflow. Install compiler versions using your normal distribution method, and place each installed version under:

```
~/.jo/compiler/<version>/
```

## Compiler Cache Layout

Each installed compiler lives under `~/.jo/compiler/<version>/`:

```
~/.jo/compiler/1.0.0/
  bin/
    jo                   # compiler + build tool
  libs/
    core.joy             # standard library
    runtime/
      python.joy         # Python platform runtime
      ruby.joy           # Ruby platform runtime
  docs/
    language/
  LICENSE
  CHANGELOG.md
```

The stdlib and platform runtimes ship as `.joy` packages inside the compiler distribution. They are loaded directly by the compiler without going through the registry.
