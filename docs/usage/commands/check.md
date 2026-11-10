# jo check

Type-check source files without generating any code.

## Usage

```
jo check [module]
```

If `module` is omitted, Jo checks the project default module.

## Examples

```sh
jo check
jo check api
```

## Notes

For apps, `jo check` uses `--check-only`. It writes `.sast` files and leaves entry-point resolution, capability injection, and code generation to `jo build`.

If `jo.lock` is missing, `jo check` resolves all modules in the project and creates it. If it exists, Jo uses it as-is.
