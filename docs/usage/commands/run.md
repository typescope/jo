# jo run

Run the application — first build if needed.

## Usage

```
jo run [module] [-- <app-args...>]
```

Only valid for app modules. If `module` is omitted, Jo runs the project default module.

Arguments after `--` are passed to the compiled application.

## What It Does

`jo run` rebuilds the app if sources or dependencies have changed, then runs the generated script using the target runtime.

If `jo.lock` is missing, `jo run` resolves all modules in the project and creates it. If it exists, Jo uses it as-is.

## Examples

```sh
jo run
jo run test
jo run -- --port 8080
```
