# Structure and Convention

Jo's syntax is designed to be clean and expressive. This document covers the language's
basic lexical structure and organizational principles.

## Program Structure

A Jo source file consists of the following syntactic elements in order:

- optional namespace declaration
- imports
- top-level definitions

### Namespaces

The namespace declaration defines the logical space that the top-level definitions
belong to:

```jo
namespace app.data

import app.model.*

class Connection(host: String, port: Int)

def connect(conn: Connection): Unit = ...
```

- Multiple source files can belong to the same namespace.

#### Default Namespaces

A file without a namespace declaration receives a **default namespace**
named `<normalized-file-name>_<content-hash>`. The filename component is the lowercase
filename without its extension, with non-identifier characters replaced by
underscores. A filename component beginning with a digit is prefixed with one
underscore. The hash is the first 16 hexadecimal characters (64 bits) of the
SHA-256 digest of the source file's UTF-8 contents.

See [JIP 0011](../../jips/0011-default-namespace-names.md) for the design rationale.

### Imports

Import statements make names from other namespaces available in the current file:

```jo
import lib.*            // Import entire namespace
import mutable.Map      // Import a specific name
import app.foo as bar   // Rename an import
```

- All imports share a single flat scope. The same name cannot be imported from two
  different sources.
- All name universes (term, type, pattern, container) for a given name are imported
  together.
- Private members are not imported.
- Names from the standard library namespace `jo` are pre-imported in an outer scope,
  available without an explicit import.

Name lookup follows this order, from highest to lowest priority:

1. **Definitions** — local bindings, parameters, and enclosing definitions, searched
   from innermost outward through the current namespace.
2. **Explicit imports** — names imported in the current file.
3. **Root namespaces** — top-level namespaces, including those from libraries.
4. **Implicit `jo` names** — names pre-imported from the standard library namespace.

The first match in the relevant name universe wins, shadowing any lower-priority
match. For example, a user namespace named `regex` takes precedence over the
implicit `jo.regex`, while an explicit `import jo.regex` takes precedence over that
user namespace.

### Top-level Definitions

Only the following definitions are allowed at top level:

- **Function definitions** (`def`)
- **Type definitions** (`type`)
- **Union definitions** (`union`)
- **Context parameter definitions** (`param`)
- **Pattern definitions** (`pattern`)
- **Section definitions** (`section`) — may contain nested top-level definitions

`val`, `var`, and `auto` definitions are only allowed inside function bodies.

Top-level definitions in the same namespace may refer to each other regardless of
declaration order.

### Entry Point

The compiler detects the entry point by name and type: the one top-level
parameterless function named `main` that returns `Unit`. Detection ignores the
`receives` clause. An error is reported if no such function exists. If several
exist, the entry point must be named explicitly:

```bash
--link jo.main=app.main
```

The detected function is then linked to `jo.main`, which the standard library
declares as:

```jo
defer def main: Unit receives IO.stdout, IO.stderr, IO.args
```

The link check requires that it receive only the context parameters that
`jo.main` declares and those that `--provide` binds (see below).

#### Injected Capabilities

The `receives` clause of `jo.main` is not an upper bound. A build can bind further
context parameters for `main` with compiler options, so that a library can offer a
capability without owning the entry point. Each option names a context parameter
and says how to provision its value, by calling a factory function or from a
literal:

```bash
jo compile --python app.jo \
  --internal fs.root=fs.Root \
  --provide  fs.readonly=fs.ReadOnly \
  --internal 'gh.repo="typescope/jo"' \
  --provide  gh.client=gh.GitHub
```

The compiler creates the values, binds them, and calls `main` with the bindings in
scope. A `--provide` parameter may be received by `main`. An `--internal`
parameter is visible only to the factories, which is how a broad capability is
narrowed before it reaches `main`: here `main` may use the read-only file system
but not the root file system it was built from. A project declares the same
options in `jo.toml` through `compile-options`, where a TOML literal string such
as `'gh.repo="typescope/jo"'` carries a Jo string literal without escapes.

A factory is a function with no parameters whose result conforms to the
parameter's type. It may receive context parameters, which other bindings or the
parameters of `jo.main` satisfy:

```jo
param root: FileSystem
param readonly: ReadOnlyFileSystem

def Root(): FileSystem = new LocalFileSystem()
def ReadOnly(): ReadOnlyFileSystem receives root = new ReadOnlyView(root)
```

A factory may live in a library passed with `--link-lib`, which the application
cannot import. The conformance check stops `main` from receiving an internal
parameter. Keeping `main` from calling a factory such as `fs.Root` directly is the
job of the [two-world separation](../../security/two-worlds.md): a factory that
creates authority from nothing uses the FFI and lives in a library the application
cannot import.

A literal is a boolean, a number, a character, or a single-line string without
interpolation. It is typed as it would be in source, with the parameter's type as
the expected type, and must conform to it. Adapters are not applied.

Bindings are instantiated once each, before `main` runs, in dependency order: the
binding of `p` depends on the binding of `q` when the factory of `p` receives `q`
and `q` is bound. Cycles are errors. Factories receive the parameters of `jo.main`
at their outer values, so a `--provide` binding may rebind one of them, for
example to prefix everything `main` writes to `IO.stdout`, and the rebinding is
seen by `main` only. An `--internal` binding may not rebind a parameter of
`jo.main`.

With bindings, `main` may receive the parameters `jo.main` declares and the
provided parameters, and nothing else. Errors in a binding quote the option, for
example `--provide fs.readonly=fs.ReadOnly: ...`, and an internal parameter no
factory receives is a warning. See
[Compiler Options](../../usage/reference/compiler-options.md) for the option
syntax.

## Syntax Conventions

### Indentation

Jo is indentation-sensitive. Block structure is determined by indentation. See
[Blocks](../expressions/blocks.md) for the full rules.

### Naming Conventions

| Kind | Convention | Examples |
|---|---|---|
| Types, patterns, sections | PascalCase | `List`, `UserAccount`, `Some` |
| Functions, variables | camelCase | `processData`, `userName` |
| Namespaces | lower-case | `app.model`, `jo.mutable` |
