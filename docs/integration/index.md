# Integrate Jo with agent frameworks

These guides show you how to add Jo to your agent. You'll give the agent a tool
that compiles and runs the Jo code it writes.

Choose the guide for your framework:

- [OpenAI Agents SDK](openai-agents-sdk.md) — add a function tool and Jo coding instructions to your agent.
- [LangChain](langchain.md) — add a Jo tool to `create_agent` alongside your existing tools.
- [Harpe](harpe.md) — configure the built-in `RunCodeTool` with your Jo API module.

## How it works

The **confined API** declares which operations generated code may call. The
**trusted implementation**, or runtime, implements those operations and enforces
access rules.

Your application (the **host**) supplies **capabilities** to the generated program.
These are API values such as `db` for database access and `stdout` for printing.

1. The agent sends generated Jo source to the added Jo tool.
2. The tool compiles it against a confined API and links a trusted implementation.
   Compilation errors go back to the agent for revision.
3. The tool runs the compiled program with capabilities chosen by the host, then
   returns its output.

![An agent sends Jo source to a tool that compiles against a confined API and returns compiler errors, links a trusted API implementation, runs the program, and returns its output or runtime error.](/img/integration-flow.svg)

The framework does not need to understand Jo. It only calls the tool and handles the
result.

## Security boundary

The model supplies source code. The trusted runtime can hold credentials and
restrict operations to the authenticated user. The host chooses that identity and
constructs the capabilities passed to generated code.

Jo checks the generated program before it runs. The host remains responsible for
authentication and for any other tools it exposes.
