# jo deps

Print the resolved dependency tree.

## Usage

```sh
jo deps [module]
```

## Output

`jo deps` prints the resolved dependency tree for a module. If `module` is omitted, Jo uses the project default module.

- source modules are shown as `[<module id>]` for the current project
- source modules from another project are shown as `<project path> [<module id>]`
- project paths are relative to the root project directory
- published packages are shown with their resolved version
- sibling entries are printed in stable lexical order by dependency name

A module id alone would be ambiguous, since two projects in one tree may both define a module called `api`. The path says which project each module came from.

## Examples

```text
[app]
  [api]
  ../agent-api [api]
  greeter-pkg 1.0.0
    jo-core 1.0.0
```

Here the `app` module of the current project depends on a sibling module `api`, on the `api` module of a separate project at `../agent-api`, and on the registry package `greeter-pkg`.
