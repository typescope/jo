# Building and Testing Jo

This document describes how to build the Jo compiler and run tests.

## Prerequisites

Install [scala-cli](https://scala-cli.virtuslab.org/)

## Building the Compiler

### Build JAR Launcher (Default)

Faster build, requires JVM at runtime:

```bash
./build
```

### Build Native Launcher

Slower build, faster startup, no JVM dependency:

```bash
./build -native
```

### Build Fat JAR

Bundle all dependencies in a single JAR file:

```bash
./build -fat
```

### Build Release Package

Create a distribution zip (requires fat JAR):

```bash
./build -fat -release
```

## Build Artifacts

After building, the following files are created:

- `bin/jo` - Unified compiler launcher (wrapper script)
- `bin/jo.jar` - JAR executable (default build)
- `bin/jo.native` - Native executable (with `-native` flag)
- `sast/stdlib/` - Precompiled standard library (.sast files)
- `sast/runtime/js/` - JavaScript runtime library
- `sast/runtime/interpreter/` - Interpreter runtime library
- `sast/runtime/native/` - Native runtime library

## Usage

The `jo` command provides a unified interface to all compiler backends:

### Run Programs (Interpreter)

```bash
# Run directly (defaults to interpreter, stdlib loaded automatically)
bin/jo tests/pos/fact.stk

# Or explicitly use the run command
bin/jo run tests/pos/fact.stk
```

### Build Applications

The standard library is automatically loaded for all commands.

**Build native executable (register machine - fastest, default):**
```bash
bin/jo build tests/pos/fact.stk -o fact
./fact
```

**Build native executable (stack machine):**
```bash
bin/jo build -stack tests/pos/fact.stk -o fact
./fact
```

**Build JavaScript application:**
```bash
bin/jo build -js tests/pos/fact.stk -o fact.js
node fact.js
```

### Build Libraries

**Build a custom library (generates .sast files):**
```bash
bin/jo build-lib lib/MyLib.stk -d build/mylib
```

**Build a library that depends on another library:**
```bash
bin/jo build-lib lib/Extensions.stk -lib build/mylib -d build/extensions
```

**Use custom libraries (stdlib is still automatically loaded):**
```bash
bin/jo build app.stk -lib build/mylib -o app
./app
```

**Use multiple libraries (colon-separated, in dependency order):**
```bash
# Core depends on nothing, Utils depends on Core, App depends on both
bin/jo build app.stk -lib build/core:build/utils -o app
./app
```

**Disable automatic stdlib loading:**
```bash
bin/jo build -no-stdlib app.stk -o app
```

## Environment Variables

- `JO_HOME` - Set automatically by the `bin/jo` wrapper script to the project root directory

## Testing

Run the full test suite:

```bash
./ci
```

This runs:
- Unit tests
- Positive tests across all backends (interpreter, JavaScript, native stack, native register)
- Warning/error message tests
- Negative tests with positional error markers
- Custom integration tests
- Demo builds

### Test Organization

- `tests/pos/` - Programs that should compile and run successfully
- `tests/warn/` - Programs that should produce warnings
- `tests/neg/` - Programs that should fail to compile
- `tests/custom/` - Custom integration test scripts

Each test in `tests/pos/` has a corresponding `.check` file with expected output.
