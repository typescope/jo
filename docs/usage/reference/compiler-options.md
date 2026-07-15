# Compiler Options Reference

These are the low-level options accepted by `jo compile`. Project commands such as
`jo build`, `jo check`, and `jo run` usually derive compiler flags from
`jo.toml`, but `module.<id>.compile-options` can pass extra flags through to `jo compile`.

For the command-level interface and examples, see [`jo compile`](../commands/compile.md).

## Option Forms

| Form | Meaning |
|------|---------|
| flag | Boolean option with no value, for example `--verbose`. |
| single value | Option followed by one value, for example `--sast .build/sast`. |
| repeatable value | Option may appear multiple times, for example `--lib core --lib util`. |
| comma list | One value split on commas, for example `--print-after namer,typer`. |

Boolean and single-value options warn if specified more than once. Repeatable options
preserve the command-line order after parsing.

## Backend Selection

Backend selectors are handled before the shared compiler options are parsed.

| Option | Description |
|--------|-------------|
| no backend selector | Type-check only. No executable or script is generated. |
| `--ruby` | Compile a Ruby application. |
| `--python` | Compile a Python application. |
| `--js` | Compile a JavaScript application. Experimental. |

## Common Options

Common options are accepted by type-check-only compilation, app backend compilation,
and experimental documentation generation.

| Option | Form | Description |
|--------|------|-------------|
| `--verbose` | flag | Show verbose compiler output. |
| `--fatal-warnings` | flag | Treat warnings as fatal. |
| `--no-stdlib` | flag | Do not load the standard library automatically. |
| `--lib <dir>` | repeatable value | Add a precompiled check-library directory. Each path must exist. |
| `--sast <dir>` | single value | Write `.sast` files to the given directory. |
| `--use-runtime-api <runtime>` | single value | Make a runtime API available as a check library. Accepted values are `python`, `ruby`, and `js`. |

When `--use-runtime-api` matches the selected app backend, the app compiler uses that
runtime API as a check library and suppresses the backend's default runtime link library.
`js` is experimental.

## Additional Checks

These options enable stricter compile-time checks.

| Option | Form | Description |
|--------|------|-------------|
| `--explicit-return-type` | flag | Require functions to declare an explicit return type. |
| `--check-shadowing` | flag | Report shadowing of local definitions. |
| `--explicit-this` | flag | Require method calls and field accesses on the current receiver to be written with an explicit `this.` selection. |
| `--no-star-import` | flag | Forbid wildcard imports such as `import foo.*`. |

## App Compilation Options

These options are accepted when compiling an application with an app backend such as
`--python`, `--ruby`, or `--js`.

| Option | Form | Description |
|--------|------|-------------|
| `-o <file>` | single value | Output file path. |
| `--link-lib <dir>` | repeatable value | Add a link-library directory used to resolve deferred definitions. Each path must exist. |
| `--link <source=target>` | repeatable value | Redirect symbol references from `source` to `target`. |

`--link` values must use `source=target` format. Both sides must be names or
dot-separated paths whose segments are identifiers, such as
`jo.Predef.entry=App.main`.

## Compiler Development Options

These options are mostly useful when working on the compiler itself.

| Option | Form | Description |
|--------|------|-------------|
| `--show-steps` | flag | Print each compiler step before it runs. |
| `--report-time` | flag | Print the compiler timing report. |
| `--check-tree` | flag | Check trees after each phase. |
| `--test-pickling` | flag | Exercise pickling as part of the compile. |
| `--print-before <steps>` | comma list | Print compiler output before the named steps. |
| `--print-after <steps>` | comma list | Print compiler output after the named steps. |
| `--print-only <filters>` | comma list | Limit `--print-before` and `--print-after` output to source files whose path contains one of the listed filters. |

## Documentation Options

`jo compile --doc` is experimental and may change. It accepts the common options
above plus these documentation-specific options.

| Option | Form | Description |
|--------|------|-------------|
| `--out <dir>` | single value | Documentation output directory. Default: `docs`. |
| `--title <name>` | single value | Documentation title. Default: `API Documentation`. |
| `--readme <file>` | single value | Markdown file to use as the generated documentation home page. |
| `--include-private` | flag | Include private symbols. |
| `--include-source` | flag | Embed source code in the generated documentation. |
