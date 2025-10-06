# Custom Integration Tests

This directory contains integration tests that demonstrate end-to-end workflows.

## Structure

Each test is in its own subdirectory containing:
- Source files (`.stk`)
- `test.sh` - Test script that builds and runs the test
- `expect.check` - Expected output

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
Demonstrates:
- Building a custom library with `build-lib`
- Using the library in an application
- Testing across all backends (interpreter, register machine, stack machine, JavaScript)
