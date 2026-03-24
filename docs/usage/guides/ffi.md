# Writing FFI Packages

An FFI package bridges Jo code to a specific platform (Python or Ruby) by calling platform-native functions.

## Declaring an FFI Package

Set `ffi` in `[package]` to declare the platform:

```toml
jo      = ">=1.0"
name    = "agent-runtime-python"

[package]
version = "1.0.0"
ffi     = "python"
```

This makes the Python runtime available as a check library during compilation, so your Jo source can call `python(...)`.

## Writing FFI Source

Use the platform escape function to inline native code:

```jo
namespace AgentRuntime

def runPython(code: String): String =
  python("run_python_code(" + code + ")")
```

The platform functions (`python`, `ruby`) are provided by the compiler's bundled runtime — no import needed.

## Implementing Deferred Definitions

An FFI package commonly implements `defer def`s from an API package:

```toml
[main.dependencies]
agent-api = "^1.0"    # provides the defer defs to implement
```

```jo
namespace AgentRuntime

// implements AgentAPI.runTask
def runTask(input: String): String =
  python("sandbox.run_task(" + input + ")")
```

The app then wires them in `[main.links]`:

```toml
[main.links]
"agentapi.runTask" = "agentruntime.runTask"
```

## FFI Contagion

`ffi` is contagious — any package that depends on an FFI package inherits its `ffi` value. An app depending on `agent-runtime-python` computes `ffi = "python"` and will be built for the Python target.

Two dependencies with conflicting `ffi` values (e.g., one requires `"python"`, another `"ruby"`) is a build error.

## Foreign Package Dependencies

List platform-native package requirements alongside your Jo source:

```
my-ffi-package/
  jo.toml
  pip.txt        # Python deps: requests, numpy, ...
  src/
    Runtime.jo
```

`jo build` merges `pip.txt` from all `.joy` deps and writes the result to `.build/<name>/pip.txt`. Use `jo deps --pip` to print the merged list.
