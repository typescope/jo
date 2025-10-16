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
- `libs/stdlib/` - Standard library (.sast files)
- `libs/runtime-js/` - JavaScript runtime library (.sast files)
- `libs/runtime-interpreter/` - Interpreter runtime library (.sast files)
- `libs/runtime-native/` - Native runtime library (.sast files)

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
