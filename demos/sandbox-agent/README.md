# Sandbox Agent Demo

An LLM-powered agent that can **only** interact with the world by generating Jo programs, compiling them to Python, and running them. The agent has access to a sandboxed filesystem via a typed Jo API.

This demonstrates how Jo's capability system can restrict an AI agent to safe, auditable operations.

## Architecture

```
User (interactive chat)
  ↕
agent.py (Python, OpenAI-compatible API)
  ↕ tool calls: runCode / compileCode
  ↓
Jo program (LLM-generated) → bin/jo build -python → execution in sandbox
  ↓ uses
FileSystemAPI.jo (interfaces) + FileSystemRuntime.jo (Python sandbox impl)
  ↓ reads
skills/ (markdown files for language reference)
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

Options:
- `--sandbox-dir` — directory the agent can read/write (required)
- `--skills-dir` — directory of skill `.md` files (default: `./skills`)
- `--api-key` — API key (or `$OPENAI_API_KEY`)
- `--base-url` — API base URL (or `$OPENAI_BASE_URL`, default: OpenAI)
- `--model` — model name (or `$MODEL`, default: gpt-4o)

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

## Security Properties

- The LLM **cannot** execute arbitrary Python — it can only write Jo programs
- File access is split into `ReadableFS` and `WritableFS` interfaces, enforced by Jo's type system
- The runtime validates all paths stay within the sandbox directory
- Path traversal (`../`) is blocked
- Skills are read-only — the agent cannot modify its own reference material
- The LLM never sees the sandbox root path or has access to raw Python APIs
- All conversations are logged to `out/conversation.jsonl` for auditing

## Files

| File | Description |
|------|-------------|
| `agent.py` | Chat agent with runCode/compileCode tools, colors, spinner, readline |
| `FileSystemAPI.jo` | Pure interface definitions (ReadableFS, WritableFS, Skills, Logger) |
| `FileSystemRuntime.jo` | Python-backed sandbox implementation |
| `build.sh` | Pre-compiles API and runtime libraries |
| `skills/` | Markdown skill files for LLM reference |
| `view-log.py` | Pretty-print `out/conversation.jsonl` as markdown |
