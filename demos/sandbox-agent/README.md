# Sandbox Agent Demo

An LLM-powered agent that can **only** interact with the world by generating Jo programs, compiling them to Python, and running them. The agent has access to a sandboxed filesystem via a typed Jo API.

This demonstrates how Jo's capability system can restrict an AI agent to safe, auditable operations.

## Architecture

```
User (interactive chat)
  ↕
agent.py (Python, OpenAI-compatible API)
  ↕ tool calls: compileJo / runJo
  ↓
Jo program (LLM-generated) → bin/pyc → Python → execution in sandbox
  ↓ uses
FileSystemAPI.jo (interfaces) + FileSystemRuntime.jo (Python sandbox impl)
```

## Setup

1. Install the `openai` Python package:
   ```bash
   pip install openai
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
  --api-key <your-api-key> \
  --base-url <api-base-url> \
  --model <model-name>
```

Environment variables are also supported:
- `OPENAI_API_KEY` — API key
- `OPENAI_BASE_URL` — API base URL (default: OpenAI)
- `MODEL` — model name (default: gpt-4o)

## Example Session

```
You: List all files in the sandbox
  [compileJo] compiling... done.
  [runJo] running... done.

Agent: The sandbox contains one file: test.txt (24 bytes).

You: Create a file called greeting.txt with "Hello World"
  [compileJo] compiling... done.
  [runJo] running... done.

Agent: Created greeting.txt successfully.

You: Read greeting.txt
  [compileJo] compiling... done.
  [runJo] running... done.

Agent: The contents of greeting.txt: "Hello World"
```

## Security Properties

- The LLM **cannot** execute arbitrary Python — it can only write Jo programs
- All file access goes through the `FileSystem` interface, enforced by Jo's type system
- The runtime validates all paths stay within the sandbox directory
- Path traversal (`../`) is blocked
- The LLM never sees the sandbox root path or has access to raw Python APIs

## Files

| File | Description |
|------|-------------|
| `agent.py` | The chat agent with compileJo/runJo tools |
| `FileSystemAPI.jo` | Pure interface definitions (types, interfaces, context params) |
| `FileSystemRuntime.jo` | Python-backed sandbox implementation |
| `build.sh` | Pre-compiles API and runtime libraries |
