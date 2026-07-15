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

Faster than `jo build` — no backend invocation. Useful for editor integration and CI feedback loops where you only need type errors, not compiled output.

If `jo.lock` is missing, `jo check` resolves all modules in the project and creates it. If it exists, Jo uses it as-is.
