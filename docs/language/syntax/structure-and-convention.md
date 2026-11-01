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
named `__<normalized-file-name>_<content-hash>`. The filename component is the lowercase
filename without its extension, with non-identifier characters replaced by
underscores. The hash is the SHA-256 digest of the source file's UTF-8 contents.

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

The compiler detects the entry point by locating a top-level function named `main`
conforming to this signature from the standard library:

```jo
defer def main: Unit receives IO.stdout, IO.stderr, IO.args
```

An error is reported if no matching candidate exists or multiple candidates are found.
The entry point can be specified explicitly with:

```bash
--link jo.main=app.main
```

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
