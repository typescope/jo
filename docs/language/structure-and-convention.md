# Structure and Convention

Jo's syntax is designed to be clean, expressive, and accessible to both humans and LLMs. This overview covers the language's basic lexical structure and organizational principles.

## Program Structure

A Jo source file consists of the following syntactic elements in order:

- namespace
- imports
- top-level definitions

### Namespaces

The namespace declaration defines the logical space that the top-level
definitions belong to:

```jo
namespace app.data

import app.model.*

class Connection(host: String, port: Int)

def connect(conn: Connection): Unit = ...
```

Rules for namespaces:

- If no namespace is specified, the filename becomes the default namespace
- Multiple source files can belong to the same namespace

### Imports

Import statements make other namespaces available:

```jo
import lib.*            // Import entire namespace
import mutable.Map      // Import a specific name
import app.foo as bar   // Rename an import
```

Rules for imports:

- The `import` statements create a single virtual scope and no duplication allowed
- The same-named members of all name universes are imported together
- Invisible members (e.g. `private`) are not imported
- The names in the standard library namespace `jo` is available in an outer scope of the virtual import scope

### Top-level Definitions

At the top level of a namespace, only the following definitions are allowed:

- **Function definitions** (`def`)
- **Type definitions** (`type`)
- **Union definitions** (`union`)
- **Context parameter definitions** (`param`)
- **Pattern definitions** (`pattern`)
- **Section definitions** (`section`) - may contain nested top-level definitions

Value definitions (`val`, `var`) and auto definitions (`auto`) can only appear inside functions, not at the top level.

The top-level definitions of the same namespace may refer to each other directly
irregardless of the order they appear in the source code.

### Entry Point

When compiling Jo source code as a runnable program, the compiler will try to
automaticlaly detect the entry point by locating a top-level function named
`main` which conforms to the following signature defined in the standard
library:

```jo
defer def main: Unit receives IO.stdin, IO.stdout, IO.stderr, IO.open, IO.args
```

An error is reported if no such candiates are found or multiple candiates are
found. The user can specify the entry point explicitly with command-line option:

```bash
-link jo.main=app.main
```

## Syntax Conventions

### Indentation

Jo is indentation-sensitive. Consistent indentation indicates block structure:

```jo
if condition then
  statement1()
  statement2()
  if nested then
    nestedStatement()
  end
end
```

### Naming Conventions

- Types: PascalCase (`List`, `UserAccount`)
- Patterns: PascalCase (`Positive`, `Some`)
- Functions: camelCase (`processData`, `validateInput`)
- Variables: camelCase (`userName`, `totalCount`)
- Sections: PascalCase (`List`, `Array`)
- Namespaces: lower-case (`app.model`, `jo.mutable`)

### Expression Forms

Jo supports multiple expression styles:

```jo
// Prefix notation
add(1, 2)
println("message")

// Infix notation (for operators)
1 + 2
x == y

// Postfix notation (for parameterless methods)
list.length
file.exists

// Mixed forms
users.select(u => u.active).map(u => u.name)
```
