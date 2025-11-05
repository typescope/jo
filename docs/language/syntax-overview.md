# Syntax Overview

Jo's syntax is designed to be clean, expressive, and accessible to both humans and LLMs. This overview covers the language's basic lexical structure and organizational principles.

## Lexical Elements

### Identifiers and Operators

```
letter   = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"
digit    = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9"
opchar   = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
           ">" | "<" | "=" | "!" | "?"

name     = (letter | "_") {letter | digit | "_"}
operator = opchar {opchar}
ident    = name | operator
```

### Literals

```
integer = ["-"] digit {digit}
boolean = "true" | "false"
char    = single character in single quotes
string  = text in double quotes
```

### Comments

Jo supports both line and block comments:

```jo
// Line comment

//[ Block comment //]

///[
  Nested block comment
  ///[ with inner blocks ///]
///]
```

Block comments require matching numbers of slashes in opening and closing delimiters.

## Keywords

Core language keywords:

```
val var fun def type data param class new alias as
if then else while do begin end match case with allow
import namespace section receives pattern auto defer
```

## Program Structure

### Namespaces

Each source file defines a namespace:

```jo
namespace io.net

import system

type Connection = { host: String, port: Int }

def connect(conn: Connection): Unit = ...
```

Rules for namespaces:

- If no namespace is specified, the filename becomes the default namespace
- Namespaces form a tree structure with branch and leaf nodes
- Branch namespaces contain only other namespaces
- Leaf namespaces contain functions and type definitions
- All dependencies must be explicitly imported

### Imports

Import statements make other namespaces available:

```jo
import lib.*            // Import entire namespace
import system.File.read // Import specific function
```

Exceptions to import requirements:

- Current namespace is implicitly imported
- Predefined language constructs are always available

### Top-level Definitions

At the top level of a namespace, only the following definitions are allowed:

- **Function definitions** (`def`)
- **Type definitions** (`type`)
- **Data definitions** (`data`)
- **Context parameter definitions** (`param`)
- **Pattern definitions** (`pattern`)
- **Alias definitions** (`alias`)
- **Section definitions** (`section`) - may contain nested top-level definitions

Value definitions (`val`, `var`) can only appear inside function bodies, not at the top level.

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
- Functions: camelCase (`processData`, `validateInput`)
- Variables: camelCase (`userName`, `totalCount`)
- Constants: camelCase (`maxRetries`, `defaultTimeout`)
- Namespaces: dot.separated (`data.collections`, `io.network`)

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
users.filter(u => u.active).map(u => u.name)
```