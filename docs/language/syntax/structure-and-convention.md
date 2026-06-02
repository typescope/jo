# Structure and Convention

Jo's syntax is designed to be clean and expressive. This document covers the language's
basic lexical structure and organizational principles.

## Program Structure

A Jo source file consists of the following syntactic elements in order:

- namespace declaration
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

- If no namespace is declared, the filename becomes the default namespace.
- Multiple source files can belong to the same namespace.

### Imports

Import statements make names from other namespaces available in the current file:

```jo
import lib.*            // Import entire namespace
import mutable.Map      // Import a specific name
import app.foo as bar   // Rename an import
```

- All imports share a single flat scope; the same name cannot be imported from two
  different sources.
- All name universes (term, type, pattern, container) for a given name are imported
  together.
- Private members are not imported.
- Names from the standard library namespace `jo` are pre-imported in an outer scope,
  available without an explicit import.

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
defer def main: Unit receives IO.stdin, IO.stdout, IO.stderr, IO.args
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
