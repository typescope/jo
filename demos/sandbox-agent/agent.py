#!/usr/bin/env python3
"""
Sandbox Agent — an LLM-powered agent that interacts with the world
exclusively through Jo programs compiled to Python.

The agent has two tools:
  compileCode(code) — write code & compile to Python
  runCode(code)     — write code, compile, and run in the sandbox

All file system access is mediated by Jo's capability system,
enforcing sandbox boundaries at the type level.
"""

import argparse
import json
import os
import subprocess
import sys

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
OUT_DIR = os.path.join(SCRIPT_DIR, "out")
TASK_JO = os.path.join(OUT_DIR, "task.jo")
TASK_PY = os.path.join(OUT_DIR, "task.py")

# ---------------------------------------------------------------------------
# System prompt components
# ---------------------------------------------------------------------------

CHEAT_SHEET = ""
cheat_sheet_path = os.path.join(PROJECT_ROOT, "docs", "overview", "cheat-sheet.md")
if os.path.exists(cheat_sheet_path):
    with open(cheat_sheet_path) as f:
        CHEAT_SHEET = f.read()

FILESYSTEM_API = ""
api_path = os.path.join(SCRIPT_DIR, "FileSystemAPI.jo")
if os.path.exists(api_path):
    with open(api_path) as f:
        FILESYSTEM_API = f.read()

STDLIB_SUMMARY = """\
## Standard Library Summary

Key types available in Jo programs:

- **Int**: integer arithmetic (+, -, *, /, %, comparisons). `x.toString` converts to String.
- **Float**: floating-point numbers. `x.toInt`, `x.toString`.
- **Bool**: `true`, `false`. Operators: `&&`, `||`, `!`.
- **Char**: single character. `c.toInt`, `c.toString`.
- **String**: immutable strings. `.size`, `.get(i)`, `.substring(from, until)`,
  `.startsWith(s)`, `.contains(s)`, `.indexOf(s)`, `.split(sep)`, `.trim`,
  `.toInt`, `.toFloat`, `+` for concatenation, `\\{expr}` for interpolation.
- **List[T]**: immutable linked list.
  - Create: `[1, 2, 3]`, `List.empty`
  - Access: `.head`, `.tail`, `.get(i)`, `.size`, `.isEmpty`
  - Transform: `.map(f)`, `.filter(f)`, `.foldLeft(init, f)`, `.flatMap(f)`
  - Build: `.prepend(x)`, `.append(x)`, `.concat(other)`
  - Convert: `.reverse`, `.take(n)`, `.drop(n)`
- **Option[T]**: `Some(value)` or `None`. `.map(f)`, `.flatMap(f)`, `.getOrElse(default)`.
- **Map[K, V]**: immutable map. `{"key": value}`, `.get(k)` returns Option, `.set(k, v)`, `.keys`, `.size`.
- **Set[T]**: immutable set. `{1, 2, 3}`, `.contains(x)`, `.add(x)`, `.remove(x)`, `.size`.
- **Pair[A, B]**: `Pair(a, b)`, `.first`, `.second`.
- **IO**: `println(msg)`, `print(msg)` — available via `receives stdout`.

## Important Notes
- Use `object None` (not `new None`) for the None singleton
- Strings use `\\{expr}` for interpolation (not `${}`)
- No semicolons; indentation-based blocks
- `begin ... end` for multi-statement expressions
- `for x in list do ...` for iteration
- `match expr case ... => ...` for pattern matching
"""

SYSTEM_PROMPT = f"""\
You are a sandboxed file-system agent. You interact with the world ONLY by
writing Jo programs, compiling them, and running them.

You have two tools:
1. **compileCode(code)** — writes your Jo code to a file and compiles it.
   Returns compiler output (success or errors). Use this to check if your
   code compiles before running it.
2. **runCode(code)** — writes your Jo code to a file, compiles it, and runs
   the compiled program inside the sandbox directory. Returns the program's
   stdout/stderr. This is the primary tool for completing tasks.

Your workflow: write Jo code → runCode → if compile errors, fix and retry → report results.
Use compileCode if you want to check compilation without running.

## Jo Language Reference

{CHEAT_SHEET}

{STDLIB_SUMMARY}

## FileSystem API (provided as context parameters)

```jo
{FILESYSTEM_API}
```

## How to Write Your Programs

Your program must follow this template:

```jo
namespace UserTask
import jo.IO.stdout
import FileSystemAPI.*

def runTask(): Unit receives stdout, fs, logger =
  // Your code here
  // Use fs.readFile, fs.writeFile, fs.listDir, fs.deleteFile, fs.exists, fs.mkdir
  // Use logger.info, logger.warn for logging
  // Use println for output
```

Key rules:
- Namespace must be `UserTask`
- Entry point must be `def runTask(): Unit receives stdout, fs, logger`
- Use `fs.*` methods for ALL file operations
- Use pattern matching on ReadResult/WriteResult for error handling
- All paths are relative to the sandbox root (use "." for root)
"""

# ---------------------------------------------------------------------------
# Tool definitions (OpenAI function-calling format)
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "runCode",
            "description": "Write Jo source code to a file, compile it to Python, and run it in the sandbox. Returns compiler output and program stdout/stderr.",
            "parameters": {
                "type": "object",
                "properties": {
                    "code": {
                        "type": "string",
                        "description": "The Jo source code to compile and run"
                    }
                },
                "required": ["code"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "compileCode",
            "description": "Write Jo source code to a file and compile it to Python. Returns compiler stdout/stderr. Does not run the program.",
            "parameters": {
                "type": "object",
                "properties": {
                    "code": {
                        "type": "string",
                        "description": "The Jo source code to compile"
                    }
                },
                "required": ["code"]
            }
        }
    }
]

# ---------------------------------------------------------------------------
# Tool implementations
# ---------------------------------------------------------------------------

def compile_code(code: str) -> tuple[bool, str]:
    """Write code to task.jo and compile it. Returns (success, output)."""
    os.makedirs(OUT_DIR, exist_ok=True)

    with open(TASK_JO, "w") as f:
        f.write(code)

    cmd = [
        os.path.join(PROJECT_ROOT, "bin", "pyc"),
        "-link", "jo.main=FileSystemRuntime.platformMain",
        "-link", "FileSystemAPI.runTask=UserTask.runTask",
        "-lib", os.path.join(OUT_DIR, "api"),
        "-runtime", os.path.join(OUT_DIR, "runtime"),
        TASK_JO,
        "-o", TASK_PY,
    ]

    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=30,
            cwd=PROJECT_ROOT
        )
        output = ""
        if result.stdout.strip():
            output += result.stdout.strip() + "\n"
        if result.stderr.strip():
            output += result.stderr.strip() + "\n"
        if result.returncode == 0:
            return True, (output + "Compilation successful.").strip()
        else:
            return False, (output + f"Compilation failed (exit code {result.returncode}).").strip()
    except subprocess.TimeoutExpired:
        return False, "Compilation timed out after 30 seconds."
    except Exception as e:
        return False, f"Compilation error: {e}"


def run_program(sandbox_dir: str) -> str:
    """Run the compiled task.py with the sandbox directory."""
    cmd = ["python3", TASK_PY, os.path.abspath(sandbox_dir)]

    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=30,
            cwd=PROJECT_ROOT
        )
        output = ""
        if result.stdout.strip():
            output += result.stdout.strip() + "\n"
        if result.stderr.strip():
            output += "[stderr] " + result.stderr.strip() + "\n"
        if not output.strip():
            output = "(no output)"
        if result.returncode != 0:
            output += f"\nProcess exited with code {result.returncode}."
        return output.strip()
    except subprocess.TimeoutExpired:
        return "Program timed out after 30 seconds."
    except Exception as e:
        return f"Runtime error: {e}"


def handle_tool_call(name: str, arguments: dict, sandbox_dir: str) -> str:
    code = arguments.get("code", "")

    if name == "compileCode":
        _, output = compile_code(code)
        return output

    elif name == "runCode":
        ok, compile_output = compile_code(code)
        if not ok:
            return compile_output
        run_output = run_program(sandbox_dir)
        return compile_output + "\n\n" + run_output

    else:
        return f"Unknown tool: {name}"

# ---------------------------------------------------------------------------
# Build check
# ---------------------------------------------------------------------------

def ensure_built():
    """Run build.sh if out/ doesn't contain compiled libraries."""
    api_dir = os.path.join(OUT_DIR, "api")
    runtime_dir = os.path.join(OUT_DIR, "runtime")

    if os.path.isdir(api_dir) and os.path.isdir(runtime_dir):
        return True

    print("Libraries not found. Building...")
    build_script = os.path.join(SCRIPT_DIR, "build.sh")
    result = subprocess.run(
        ["bash", build_script],
        capture_output=True, text=True, cwd=SCRIPT_DIR
    )
    if result.returncode != 0:
        print("Build failed:")
        print(result.stdout)
        print(result.stderr)
        return False

    print("Build complete.")
    return True

# ---------------------------------------------------------------------------
# Chat loop
# ---------------------------------------------------------------------------

def chat_loop(sandbox_dir: str, api_key: str, base_url: str, model: str):
    try:
        from openai import OpenAI
    except ImportError:
        print("Error: openai package not installed. Run: pip install openai")
        sys.exit(1)

    client = OpenAI(api_key=api_key, base_url=base_url)

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]

    print(f"Sandbox agent ready. Sandbox directory: {os.path.abspath(sandbox_dir)}")
    print(f"Model: {model}")
    print("Type your request (Ctrl+D or 'quit' to exit).\n")

    while True:
        try:
            user_input = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye.")
            break

        if not user_input:
            continue
        if user_input.lower() in ("quit", "exit"):
            print("Goodbye.")
            break

        messages.append({"role": "user", "content": user_input})

        # Agent loop: keep going until the LLM produces a non-tool response
        while True:
            try:
                response = client.chat.completions.create(
                    model=model,
                    messages=messages,
                    tools=TOOLS,
                    tool_choice="auto",
                )
            except Exception as e:
                print(f"\nAPI error: {e}\n")
                messages.pop()
                break

            choice = response.choices[0]
            msg = choice.message

            if msg.tool_calls:
                messages.append(msg)

                for tool_call in msg.tool_calls:
                    fn_name = tool_call.function.name
                    try:
                        fn_args = json.loads(tool_call.function.arguments)
                    except json.JSONDecodeError:
                        fn_args = {}

                    if fn_name == "runCode":
                        print(f"  [{fn_name}] compiling & running...", end="")
                    else:
                        print(f"  [{fn_name}] compiling...", end="")

                    result = handle_tool_call(fn_name, fn_args, sandbox_dir)
                    print(" done.")

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": result,
                    })

                continue

            if msg.content:
                print(f"\nAgent: {msg.content}\n")
                messages.append({"role": "assistant", "content": msg.content})
            break

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Sandbox agent — LLM writes Jo programs to interact with a sandboxed filesystem"
    )
    parser.add_argument(
        "--sandbox-dir", required=True,
        help="Directory to use as the sandbox root"
    )
    parser.add_argument(
        "--api-key", default=os.environ.get("OPENAI_API_KEY", ""),
        help="API key (default: $OPENAI_API_KEY)"
    )
    parser.add_argument(
        "--base-url", default=os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1"),
        help="API base URL (default: $OPENAI_BASE_URL or OpenAI)"
    )
    parser.add_argument(
        "--model", default=os.environ.get("MODEL", "gpt-4o"),
        help="Model name (default: $MODEL or gpt-4o)"
    )

    args = parser.parse_args()

    if not args.api_key:
        print("Error: --api-key or $OPENAI_API_KEY required")
        sys.exit(1)

    os.makedirs(args.sandbox_dir, exist_ok=True)

    if not ensure_built():
        sys.exit(1)

    chat_loop(args.sandbox_dir, args.api_key, args.base_url, args.model)


if __name__ == "__main__":
    main()
