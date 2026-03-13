# Managing Dependencies

## Adding a Dependency

Add a version range to `[main.dependencies]`:

```toml
[main.dependencies]
jo-core = "^1.0.0"
```

Then run `jo build` — the resolver fetches and locks the dependency automatically.

## Link Libraries

A link library resolves `defer def`s at link time but is hidden from user code:

```toml
[main.dependencies]
agent-api            = "^1.0.0"
agent-runtime-python = { version = "^1.0.0", link = true }
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
jo-test = "^0.1.0"
```

## The Lock File

`jo build` writes a `jo.lock` (named after the spec: `agent-api.toml` → `agent-api.lock`) recording the exact resolved versions and sha512 digests.

- **Apps**: commit `jo.lock` for reproducible builds
- **Libraries**: add `*.lock` to `.gitignore` — let consumers resolve

## Updating Dependencies

Re-resolve all dependencies and rewrite the lock file:

```sh
jo update
```

Update a specific package only:

```sh
jo update jo-core
```

## Viewing Dependencies

```sh
jo deps              # show resolved Jo dependency tree
jo deps --pip        # show merged Python foreign deps
jo deps --gems       # show merged Ruby foreign deps
jo deps --npm        # show merged JS foreign deps
```
