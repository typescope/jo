# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the implementation of **Jo**, a statically typed functional programming language with advanced type system features. The language is implemented in Scala and includes multiple compilation backends targeting JavaScript and native Linux/x86 code.

## Development Commands

### Build System

Warning: Never re-build the whole project during development. Use the following scirpts instead:

- **Parser only**: `bin/parse <file.jo>` - Parse and output AST
- **Type checker only**: `bin/type <file.jo>` - Type check without compilation
- **Interpreter only**: `bin/run <file.jo>` - Type check and execute the code
- **JavaScript only**: `bin/jsc <file.jo>` - Type check and compile code to JavaScript

### Main Compiler (after build)
The `bin/jo` command provides a unified interface to all compilation backends:

- **Interpreter**: `bin/jo run <file.jo>` - Direct interpretation
- **Build application (native/register)**: `bin/jo build <file.jo> -o <executable>` - Default backend
- **Build application (native/stack)**: `bin/jo build -stack <file.jo> -o <executable>` - Stack machine backend
- **Build application (JavaScript)**: `bin/jo build -js <file.jo> -o <output.js>` - JavaScript backend
- **Build library**: `bin/jo build-lib <file.jo> -d <dir>` - Generate .sast files
- **Use precompiled library**: `bin/jo build <file.jo> -lib <dir> -o <executable>` - Build with precompiled library
- **Direct run**: `bin/jo <file.jo>` - Shorthand for `run` command

### Test Organization
- **Positive tests**: `tests/pos/` - Programs that should compile and run successfully
- **Warning tests**: `tests/warn/` - Programs that should produce warnings
- **Negative tests**: `tests/neg/` - Programs that should fail to compile

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
- **Native Stack** (`native/stack/`) - Stack machine based native compilation
- **Native Register** (`native/register/`) - Register machine based native compilation
- **Assembly** (`native/Assembly.scala`) - Low-level assembly generation

#### Runtime Systems
- **JavaScript Runtime** (`runtime/JS.jo`) - JS-specific runtime functions
- **Native Runtime** (`runtime/native/`) - Native runtime including GC and syscalls
- **Standard Library** (`lib/`) - Core language libraries (Array, Bool, Int, List, etc.)

### Key Design Patterns
- **Symbol-based resolution** - All names resolved to symbols in SAST
- **Phase-based compilation** - Modular transformation pipeline
- **Multi-backend architecture** - Shared frontend with pluggable backends
- **Effect system** - Fine-grained effect tracking and control
- **Context parameters** - Advanced parameter passing mechanisms

## Language Features Implemented
- Context parameters for implicit parameter passing
- Tagged algebraic data types with pattern matching
- Effect system with parametric effects
- Flexible syntax supporting prefix/infix/postfix operators
- Indentation-based syntax
- Polymorphic functions and type lambdas
- Record types and object types
- Namespace system with explicit imports

## Testing Strategy
- Each test case in `tests/pos/` has a corresponding `.check` file with expected output
- Tests are run against all compilation backends to ensure consistency
- Warning tests validate compiler diagnostic messages
- Negative tests ensure proper error handling and reporting
