# Integrate Jo with agent frameworks

It's easy to integrate Jo to an existing agent framework. Keep your existing model, agent loop, and
tool calling, just add one tool that accepts a Jo program and returns its output.

## How it works

1. The agent sends generated Jo source to the added Jo tool.
2. The tool compiles it against a confined API and links a trusted implementation.
   Compilation errors go back to the agent for revision.
3. The tool runs the compiled program with capabilities chosen by the host, then
   returns its output.

![An agent sends Jo source to a tool that compiles against a confined API, links a trusted API implementation, runs the program, and returns a result or error.](/img/integration-flow.svg)

The framework does not need to understand Jo. It only calls the tool and handles the
result.

## Security boundary

The confined API determines what generated code may call. The trusted implementation
can hold credentials and enforce policy, such as restricting queries to the authenticated

Jo checks the generated program before it runs. The host remains responsible for
authentication and for any other tools it exposes.
