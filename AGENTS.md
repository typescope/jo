# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the implementation of **Jo**, a statically typed functional programming language with advanced type system features. The language is implemented in Scala and includes multiple compilation backends targeting JavaScript, Ruby, Python, native 32-bit Linux/x86 code, and 64-bit code through LLVM.

## Language Documentation

Language documentation is in `docs/`. Key entry points:
- [Language Tour](docs/overview/language-tour.md) - Overview of features with examples
- [Cheat Sheet](docs/overview/cheat-sheet.md) - A cheat sheet for major language features
- [Syntax Summary](docs/language/syntax/syntax-summary.md) - Formal grammar
- [Design Principles](docs/language/design-principles.md) - Core design philosophy

## Development Commands

### Build System

Warning: Never re-build the whole project during development. Use the following scripts instead:

- **Parser only**: `bin/parse <file.jo>` - Parse and output AST
- **Type checker only**: `bin/type <file.jo>` - Type check without compilation
- **Interpreter only**: `bin/run <file.jo>` - Type check and execute the code
- **JavaScript only**: `bin/jsc <file.jo>` - Type check and compile code to JavaScript
- **Ruby only**: `bin/rbc <file.jo>` - Type check and compile code to Ruby
- **Python only**: `bin/pyc <file.jo>` - Type check and compile code to Python
- **LLVM only**: `bin/llvmc <file.jo>` - Type check and compile code to a 64-bit executable with clang

### Main Compiler (after build)
The `bin/jo` command provides a unified interface to all compilation backends:

- **Interpreter**: `bin/jo run <file.jo>` - Direct interpretation
- **Build application (native/register)**: `bin/jo compile --reg <file.jo> -o <executable>` - Register machine backend
- **Build application (native/stack)**: `bin/jo compile --stack <file.jo> -o <executable>` - Stack machine backend
- **Build application (LLVM)**: `bin/jo compile --llvm <file.jo> -o <executable>` - 64-bit backend, emits LLVM IR and needs clang 15 or newer (`--clang <path>` picks the executable)
- **Build application (JavaScript)**: `bin/jo compile --js <file.jo> -o <output.js>` - JavaScript backend
- **Build library**: `bin/jo compile --sast <dir> <file.jo>` - Generate `.sast` files
- **Use precompiled library**: `bin/jo compile --reg <file.jo> --lib <dir> -o <executable>` - Build with a precompiled library
- **Direct run**: `bin/jo <file.jo>` - Shorthand for `run` command

### Test Organization
- **Positive tests**: `tests/pos/` - Programs that should compile and run successfully
- **Warning tests**: `tests/warn/` - Programs that should produce warnings

## Code Architecture

### Core Pipeline
The compilation process follows these phases:
1. **Parsing** (`parsing/`) - Lexical analysis and syntax tree construction
2. **Type Checking** (`typing/`) - Type inference and semantic analysis
3. **SAST Generation** (`sast/`) - Semantic Abstract Syntax Tree construction
4. **Frontend Processing** (`phases/FrontEnd.scala`) - Applies transformation phases:
   - `Pickler` - Serialization support
   - `NormalizeParams` - Parameter normalization
   - `PatternMatcher` - Pattern matching lowering
   - `EncodeTagged` - Tagged value encoding
5. **Backend Compilation** - Target-specific code generation

### Key Components

#### Frontend (`stack-lang/`)
- **AST** (`ast/`) - Abstract syntax tree definitions and operations
- **Parsing** (`parsing/`) - Scanner, parser, and token definitions
- **Typing** (`typing/`) - Type system implementation including inference
- **SAST** (`sast/`) - Semantic analysis and symbol resolution
- **Phases** (`phases/`) - Compilation phase implementations

#### Backends
- **JavaScript** (`js/`) - JavaScript code generation and runtime
- **Ruby** (`ruby/`) - Ruby code generation and runtime
- **Python** (`python/`) - Python code generation and runtime
- **Native Stack** (`native/stack/`) - Stack machine based native compilation
- **Native Register** (`native/register/`) - Register machine based native compilation
- **Assembly** (`native/Assembly.scala`) - Low-level assembly generation
- **LLVM** (`llvm/`) - 64-bit native compilation through LLVM IR and clang, see `llvm/DESIGN.md`

#### Runtime Systems
- **JavaScript Runtime** (`runtime/JS.jo`) - JS-specific runtime functions
- **Ruby Runtime** (`runtime/Ruby.jo`) - Ruby-specific runtime functions
- **Python Runtime** (`runtime/Python.jo`) - Python-specific runtime functions
- **Native Runtime** (`runtime/native/`) - Native runtime: `common/` is shared, `linux32/` is the 32-bit raw-syscall layer, `libc/` is the LLVM layer on the C library
- **Standard Library** (`lib/`) - Core language libraries (Array, Bool, Int, List, etc.)

### Key Design Patterns
- **Symbol-based resolution** - All names resolved to symbols in SAST
- **Phase-based compilation** - Modular transformation pipeline
- **Multi-backend architecture** - Shared frontend with pluggable backends
- **Effect system** - Fine-grained effect tracking and control
- **Context parameters** - Advanced parameter passing mechanisms

## Language Features Implemented
- Context parameters for implicit parameter passing
- Union types where each branch must be a class (including primitive types)
- Pattern matching (lowered to if/else chains in frontend)
- Effect system with parametric effects
- Flexible syntax supporting prefix/infix/postfix operators
- Indentation-based syntax
- Polymorphic functions and type lambdas
- Interface types with view-based implementation
- Namespace system with explicit imports

## Testing Strategy
- Each test case in `tests/pos/` has a corresponding `.check` file with expected output
- Tests are run against all compilation backends to ensure consistency
- Warning tests validate compiler diagnostic messages
- Negative tests ensure proper error handling and reporting

## Investigating Compiler Bugs
- Check the documented language contract before treating different backend results as a bug.
- Weigh the runtime cost on valid programs before defining behavior for previously undefined inputs. Keep behavior undefined when enforcing consistency would add overhead to every operation without a clear benefit.
- State the full valid input range in the documentation, including whether negative values are excluded. Fix backend differences for inputs whose behavior is defined.

## Code Quality Guidelines

The compiler is written in Scala 3. Follow these conventions when writing or editing code:

- **Scala 3 syntax**: use `end` markers, optional braces, `?=>` context functions, `given`/`using`, etc.
- **No semicolons**
- **Blank line between `match` case branches** when any branch body is more than one line
- **Blank line before and after control constructs** (`if`, `while`, `for`) when they appear inside a larger block, to visually separate them from surrounding statements
- **Blank line after a block-ending construct** before the next statement
