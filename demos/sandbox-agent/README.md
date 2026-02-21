# Sandbox Agent Demo

An LLM-powered agent that can **only** interact with the world by generating Jo programs, compiling them to Python, and running them. The agent has access to a sandboxed workspace via a typed Jo API, with extensible actions for additional capabilities.

This demonstrates how Jo's capability system can restrict an AI agent to safe, auditable operations.

## Architecture

```
User (interactive chat)
  ↕
agent.py (Python, Anthropic Claude API)
  ↕ tool calls: runCode / compileCode
  ↓
Jo program (LLM-generated) → bin/jo build -python → execution in sandbox
  ↓ uses
AgentAPI.jo (interfaces) + AgentRuntime.jo (runtime implementation)
  ↓ reads
skills/ (markdown files for language reference)
```

## Setup

1. Install the `anthropic` Python package:
   ```bash
   pip install anthropic
   ```

2. Build the libraries (done automatically on first run, or manually):
   ```bash
   bash build.sh
   ```

3. Create a sandbox directory with some test files:
   ```bash
   mkdir -p sandbox
   echo "Hello from the sandbox!" > sandbox/test.txt
   ```

## Usage

```bash
python3 agent.py \
  --sandbox-dir sandbox \
  --api-key <your-anthropic-api-key> \
  --model claude-opus-4-6
```

Options:
- `--sandbox-dir` — directory the agent can read/write (required)
- `--skills-dir` — directory of skill `.md` files (default: `./skills`)
- `--api-key` — Anthropic API key (or `$ANTHROPIC_API_KEY`)
- `--model` — model name (or `$MODEL`, default: `claude-opus-4-6`)

## Example Session

```
You ▸ List all files in the sandbox
  ✓ compiled & ran (2.1s)

Agent ▸ The sandbox contains one file: test.txt (24 bytes).

You ▸ Create a file called greeting.txt with "Hello World"
  ✓ compiled & ran (1.8s)

Agent ▸ Created greeting.txt successfully.

You ▸ Read greeting.txt
  ✓ compiled & ran (1.9s)

Agent ▸ The contents of greeting.txt: "Hello World"
```

## Skills

Skills are read-only markdown files that provide reference material to the LLM agent. They are loaded via the `Skills` interface (list, read, grep).

Default skills in `skills/`:
- `jo-cheat-sheet` — Jo syntax quick reference
- `stdlib` — Standard library API (String, List, Map, Set, Option, etc.)

Skills can be organized hierarchically in subdirectories (e.g. `cooking/pasta.md` → skill name `cooking/pasta`). Add custom skills by placing `.md` files in the skills directory.

## Actions

Actions are pre-vetted operations exposed to the LLM agent through a typed Jo interface. The agent calls them like regular methods (`actions.hello("World")`), and they run within the same capability system as everything else.

Built-in actions:
- `hello(name)` — returns a greeting (demo)
- `echo(message)` — returns the input (demo)
- `httpGet(url, path)` — fetches a URL and saves to a workspace file (rejects if file already exists)

### Adding a New Action

1. Add the method signature to the `Actions` interface in `AgentAPI.jo`:
   ```jo
   interface Actions
     // ... existing methods ...
     def myAction(arg: String): Result    // <-- add here
   end
   ```

2. Implement it in `ActionsImpl` in `AgentRuntime.jo`:
   ```jo
   class ActionsImpl(workspaceFS: SandboxedFS)
     // ... existing methods ...
     def myAction(arg: String): Result =
       // implementation here
       // use workspaceFS for sandboxed file access
       // use python "..." for shell/network/etc.
     view AgentAPI.Actions
   end
   ```

3. Run `bash build.sh`

The system prompt updates automatically (it reads `AgentAPI.jo`), so the LLM will see the new action immediately.

Available result types for actions:
- `Result` — generic `Success | Error(message)` for operations that may fail
- `ReadResult`, `WriteResult`, `ListResult` — for file-specific operations

## Security Properties

- The LLM **cannot** execute arbitrary Python — it can only write Jo programs
- Workspace access is mediated by `WritableFS` interface, enforced by Jo's type system
- The runtime validates all paths stay within the workspace directory
- Actions receive `SandboxedFS` for file access — they cannot bypass path validation
- Path traversal (`../`) is blocked
- Skills are read-only — the agent cannot modify its own reference material
- The LLM never sees the sandbox root path or has access to raw Python APIs
- All conversations are logged to `out/conversation.jsonl` for auditing

## Files

| File | Description |
|------|-------------|
| `agent.py` | Chat agent with runCode/compileCode tools, colors, spinner, readline |
| `AgentAPI.jo` | Interface definitions (ReadableFS, WritableFS, Skills, Logger, Actions) |
| `AgentRuntime.jo` | Python-backed runtime implementation |
| `build.sh` | Pre-compiles API and runtime libraries |
| `skills/` | Markdown skill files for LLM reference |
| `view-log.py` | Pretty-print `out/conversation.jsonl` as markdown |
