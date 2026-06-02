# Deferred Functions

A deferred function declares a function interface without providing an implementation.
It must be bound to a concrete implementation at compile time via the `--link` compiler
option.

## Syntax

```
defer_def = "defer" "def" ident [type_params] [params] ":" return_type ["receives" effects]
```

```jo
defer def connect(host: String): Unit
defer def log(msg: String): Unit receives IO.stdout
```

## Linking

Use `--link source=target` to bind a deferred function to an implementation:

```bash
bin/jo compile --python app.jo --link Source.func=Target.impl -o app.py
```

`--link` may be specified multiple times. User-supplied mappings take precedence over
compiler defaults.

The special path `jo.main` designates the program entry point:

```bash
bin/jo compile --python app.jo --link jo.main=MyApp.startup -o app.py
```

## Rules

- The linked target must conform to the deferred function's type signature; mismatches
  are reported at compile time.
- A deferred function with no `--link` binding and no default implementation is a
  compile-time error.
- Deferred functions may have context parameters (`receives`).

## See Also

  and worked examples
