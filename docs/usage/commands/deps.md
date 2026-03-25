# jo deps

Print the resolved dependency tree.

## Usage

```sh
jo deps [--spec <file.toml>]
```

## Output

`jo deps` prints the resolved dependency tree for the project.

- `app [main]` shows dependencies reachable from the main module
- `app [test]` shows only additional dependencies introduced from the test module
- local path projects are shown by project name
- published packages are shown with their resolved version

## Example

```text
app [main]
  helpers
    jo-core 1.0.0
  greeter-pkg 1.0.0
    jo-core 1.0.0

app [test]
  test-pkg 1.0.0
    jo-core 1.0.0
```
