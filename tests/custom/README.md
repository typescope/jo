# Custom Integration Tests

This directory contains integration tests that demonstrate end-to-end workflows for separate compilation and library management.

## Structure

Each test is in its own subdirectory containing:
- Source files (`.stk`)
- `test.sh` - Test script that builds and runs the test
- `expect.check` - Expected output (if applicable)

## Running Tests

Run all custom tests:
```bash
./ci  # Runs all tests including custom tests
```

Run a specific custom test:
```bash
tests/custom/mylib/test.sh
```

## Test Cases

### mylib

**Purpose:** Basic library compilation and usage

Demonstrates:

- Building a custom library with `build-lib`
- Using the library in an application
- Testing across all backends (interpreter, register machine, stack machine, JavaScript)

### cross-module

**Purpose:** Cross-module type and function references

Demonstrates:

- Library A defining types (Point, Color)
- Library B importing and using types from Library A
- Application using both libraries
- Type references across module boundaries

### transitive-deps

**Purpose:** Transitive dependency handling

Demonstrates:

- Core library defining Result[T] type
- Validation library depending on Core
- Processor library depending on both Core and Validation
- Application correctly resolving transitive dependencies
- Proper library ordering in compilation
- Using colon-separated library paths: `-lib build/core:build/validation:build/processor`
