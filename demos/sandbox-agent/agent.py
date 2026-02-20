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
import datetime
import json
import os
import readline
import subprocess
import sys
import threading
import time

# ---------------------------------------------------------------------------
# Line editing (readline)
# ---------------------------------------------------------------------------

HISTORY_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "out", ".chat_history"
)

def init_readline():
    """Enable line editing with persistent history."""
    readline.parse_and_bind("tab: complete")
    readline.set_auto_history(True)
    os.makedirs(os.path.dirname(HISTORY_FILE), exist_ok=True)
    try:
        readline.read_history_file(HISTORY_FILE)
    except FileNotFoundError:
        pass
    import atexit
    atexit.register(readline.write_history_file, HISTORY_FILE)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
OUT_DIR = os.path.join(SCRIPT_DIR, "out")
TASK_JO = os.path.join(OUT_DIR, "task.jo")
TASK_PY = os.path.join(OUT_DIR, "task.py")
LOG_FILE = os.path.join(SCRIPT_DIR, "out", "conversation.jsonl")

# ---------------------------------------------------------------------------
# Colors and styling
# ---------------------------------------------------------------------------

class Style:
    RESET   = "\033[0m"
    BOLD    = "\033[1m"
    DIM     = "\033[2m"
    # Colors
    CYAN    = "\033[36m"
    GREEN   = "\033[32m"
    YELLOW  = "\033[33m"
    RED     = "\033[31m"
    MAGENTA = "\033[35m"
    BLUE    = "\033[34m"
    WHITE   = "\033[37m"

    @staticmethod
    def enabled():
        """Check if terminal supports colors."""
        return hasattr(sys.stdout, 'isatty') and sys.stdout.isatty()

# Disable colors if not a tty
if not Style.enabled():
    for attr in ['RESET', 'BOLD', 'DIM', 'CYAN', 'GREEN', 'YELLOW',
                 'RED', 'MAGENTA', 'BLUE', 'WHITE']:
        setattr(Style, attr, '')

S = Style

class Spinner:
    """Animated spinner for long-running operations."""
    FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]

    def __init__(self, message: str, color: str = S.YELLOW):
        self.message = message
        self.color = color
        self._stop = threading.Event()
        self._thread = None

    def _animate(self):
        i = 0
        while not self._stop.is_set():
            frame = self.FRAMES[i % len(self.FRAMES)]
            print(f"\r  {self.color}{frame}{S.RESET} {self.message}", end="", flush=True)
            i += 1
            self._stop.wait(0.08)

    def __enter__(self):
        self._thread = threading.Thread(target=self._animate, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *args):
        self._stop.set()
        self._thread.join()
        # Clear the spinner line
        print(f"\r\033[2K", end="")


def print_banner():
    print(f"""
{S.CYAN}{S.BOLD}  ╔═══════════════════════════════════════╗
  ║       Jo Sandbox Agent                ║
  ╚═══════════════════════════════════════╝{S.RESET}
""")

def print_status(label: str, value: str):
    print(f"  {S.DIM}{label}:{S.RESET} {value}")

def print_tool_result(name: str, success: bool, elapsed: float):
    icon = f"{S.GREEN}✓{S.RESET}" if success else f"{S.RED}✗{S.RESET}"
    label = "compiled & ran" if name == "runCode" else "compiled"
    print(f"  {icon} {S.DIM}{label}{S.RESET} {S.DIM}({elapsed:.1f}s){S.RESET}")

def print_error(msg: str):
    print(f"\n  {S.RED}● {msg}{S.RESET}\n")

def print_warning(msg: str):
    print(f"  {S.YELLOW}● {msg}{S.RESET}")

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
- Multi-line strings use triple quotes (`\"\"\"`). Content must start on a new line after the opening quotes. No escape needed for newlines inside. Example:
  ```
  val html = \"\"\"
    <div>hello</div>
    \"\"\"
  ```
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
        os.path.join(PROJECT_ROOT, "bin", "jo"),
        "build", "-python",
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

    print(f"  {S.YELLOW}Libraries not found. Building...{S.RESET}")
    build_script = os.path.join(SCRIPT_DIR, "build.sh")
    result = subprocess.run(
        ["bash", build_script],
        capture_output=True, text=True, cwd=SCRIPT_DIR
    )
    if result.returncode != 0:
        print_error("Build failed:")
        print(result.stdout)
        print(result.stderr)
        return False

    print(f"  {S.GREEN}Build complete.{S.RESET}")
    return True

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

def log_message(entry: dict):
    """Append a JSON entry to the conversation log."""
    os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
    entry["timestamp"] = datetime.datetime.now().isoformat()
    with open(LOG_FILE, "a") as f:
        f.write(json.dumps(entry, default=str) + "\n")

# ---------------------------------------------------------------------------
# Chat loop
# ---------------------------------------------------------------------------

def chat_loop(sandbox_dir: str, api_key: str, base_url: str, model: str):
    try:
        from openai import OpenAI
    except ImportError:
        print_error("openai package not installed. Run: pip install openai")
        sys.exit(1)

    client = OpenAI(api_key=api_key, base_url=base_url, timeout=120)
    init_readline()

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    log_message({"role": "system", "content": "(system prompt)"})

    print_banner()
    print_status("Sandbox", os.path.abspath(sandbox_dir))
    print_status("Model  ", model)
    print_status("Log    ", LOG_FILE)
    print(f"\n  {S.DIM}Type your request (Ctrl+D or 'quit' to exit){S.RESET}\n")
    print(f"  {S.DIM}{'─' * 40}{S.RESET}\n")

    while True:
        try:
            # Wrap ANSI codes in \001..\002 so readline computes prompt width correctly
            prompt = f"\001{S.BOLD}{S.GREEN}\002You ▸\001{S.RESET}\002 "
            user_input = input(prompt).strip()
        except (EOFError, KeyboardInterrupt):
            print(f"\n{S.DIM}Goodbye.{S.RESET}")
            break

        if not user_input:
            continue
        if user_input.lower() in ("quit", "exit"):
            print(f"{S.DIM}Goodbye.{S.RESET}")
            break

        messages.append({"role": "user", "content": user_input})
        log_message({"role": "user", "content": user_input})

        # Agent loop: keep going until the LLM produces a non-tool response
        while True:
            try:
                with Spinner(f"{S.DIM}thinking...{S.RESET}", S.MAGENTA):
                    response = client.chat.completions.create(
                        model=model,
                        messages=messages,
                        tools=TOOLS,
                        tool_choice="auto",
                    )
            except KeyboardInterrupt:
                print_warning("Request cancelled.")
                break
            except Exception as e:
                err_str = str(e)
                if "rate_limit" in err_str or "429" in err_str:
                    import re
                    match = re.search(r'try again in ([\d.]+)s', err_str)
                    wait = float(match.group(1)) if match else 5.0
                    print_warning(f"Rate limited. Retrying in {wait:.0f}s...")
                    time.sleep(wait)
                    continue
                if "timed out" in err_str.lower() or "timeout" in err_str.lower():
                    print_warning("Request timed out. Try again or simplify your request.")
                    messages.pop()
                elif "tool_call_ids" in err_str or "tool_calls" in err_str:
                    while messages and hasattr(messages[-1], 'tool_calls'):
                        messages.pop()
                    print_warning("Recovered from malformed message state. Please try again.")
                else:
                    print_error(f"API error: {e}")
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

                    spinner_msg = "compiling & running..." if fn_name == "runCode" else "compiling..."

                    log_message({"role": "tool_call", "name": fn_name, "arguments": fn_args})

                    t0 = time.time()
                    try:
                        with Spinner(spinner_msg, S.YELLOW):
                            result = handle_tool_call(fn_name, fn_args, sandbox_dir)
                        success = "failed" not in result.lower().split('\n')[-1]
                    except Exception as e:
                        result = f"Internal error: {e}"
                        success = False
                    elapsed = time.time() - t0

                    print_tool_result(fn_name, success, elapsed)

                    log_message({"role": "tool_result", "name": fn_name, "content": result})

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": result,
                    })

                continue

            if msg.content:
                print(f"\n{S.BOLD}{S.CYAN}Agent ▸{S.RESET} {msg.content}\n")
                messages.append({"role": "assistant", "content": msg.content})
                log_message({"role": "assistant", "content": msg.content})
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
        print_error("--api-key or $OPENAI_API_KEY required")
        sys.exit(1)

    os.makedirs(args.sandbox_dir, exist_ok=True)

    if not ensure_built():
        sys.exit(1)

    chat_loop(args.sandbox_dir, args.api_key, args.base_url, args.model)


if __name__ == "__main__":
    main()
