# Managing Dependencies

## Adding a Dependency

Add a version range to `[main.dependencies]`:

```toml
[main.dependencies]
mustache = "^1.0"
```

Then run `jo build` — the resolver fetches the dependency and writes `jo.lock`.

## Link Libraries

A link library resolves `defer def`s at link time but is hidden from user code:

```toml
[main.dependencies]
agent-api            = "^1.0"
agent-runtime-python = { version = "^1.0", link = true }
```

## Path Dependencies

Reference a local project instead of a registry package:

```toml
[main.dependencies]
# Directory with a single jo.toml
agent-api = { path = "../agent-api" }

# Directory with multiple specs — pick one explicitly
agent-api = { path = "../agent-api", spec = "api.toml" }

# Same directory, different spec
agent-api = { path = ".", spec = "api.toml" }
```

Path dependencies bypass the registry and lock file entirely.

## Test Dependencies

Dependencies used only during `jo test` go in `[test.dependencies]`:

```toml
[test.dependencies]
jo-test = "^0.1"
```

## The Lock File

`jo build` writes a `jo.lock` (named after the spec: `agent-api.toml` → `agent-api.lock`) recording the exact resolved versions and sha512 digests.

Once that lock file exists, later `jo build`, `jo run`, and `jo test` use it strictly. If it no longer matches the current dependency constraints, the build fails and you must run `jo lock`.

- **Apps**: commit `jo.lock` for reproducible builds
- **Libraries**: add `*.lock` to `.gitignore` — let consumers resolve

## Updating Dependencies

Resolve dependencies and rewrite the lock file explicitly:

```sh
jo lock
```

## Viewing Dependencies

```sh
jo deps              # show resolved Jo dependency tree
jo deps --pip        # show merged Python foreign deps
jo deps --gems       # show merged Ruby foreign deps
```
