# Writing FFI Packages

An FFI package bridges Jo code to a specific platform (Python or Ruby) by calling platform-native functions.

## Declaring an FFI Package

Set `runtime` in `[package]` to declare the required runtime:

```toml
jo      = "1.0"
name    = "agent-runtime-python"

[package]
version = "1.0.0"
runtime = "python"
```

This makes the Python runtime available as a check library during compilation, so your Jo source can call `python(...)`.

## Writing FFI Source

Use the platform escape function to inline native code:

The argument to `python(...)` or `ruby(...)` must be a string literal.

```jo
namespace AgentRuntime

def pythonVersion(): String =
  python("platform.python_version()")
```

The platform functions (`python`, `ruby`) are provided by the compiler's bundled runtime — no import needed.

## Implementing Deferred Definitions

An FFI package commonly implements `defer def`s from an API package:

```toml
[main.dependencies]
agent-api = "1.0"    # provides the defer defs to implement
```

```jo
namespace AgentRuntime

// implements AgentAPI.runTask
def runTask(_input: String): String =
  python("sandbox.run_task()")
```

The app then wires them in `[main.links]`:

```toml
[main.links]
"agentapi.runTask" = "agentruntime.runTask"
```

## Runtime Contagion

`runtime` is contagious — any package that depends on an FFI package inherits its `runtime` value. An app depending on `agent-runtime-python` computes `runtime = "python"` and will be built for the Python target.

Two dependencies with conflicting `runtime` values (e.g., one requires `"python"`, another `"ruby"`) is a build error.
