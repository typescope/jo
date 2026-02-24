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

try:
    from rich.console import Console as RichConsole
    from rich.markdown import Markdown as RichMarkdown
    _rich_console = RichConsole()
    _rich_available = True
except ImportError:
    _rich_available = False

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
{S.CYAN}{S.BOLD}
  ╔═══════════════════════════════════════╗
  ║       Jo Sandbox Agent                ║
  ╚═══════════════════════════════════════╝
{S.RESET}
""")

def print_status(label: str, value: str):
    print(f"  {S.DIM}{label}:{S.RESET} {value}")

def print_tool_result(success: bool, elapsed: float):
    icon = f"{S.GREEN}✓{S.RESET}" if success else f"{S.RED}✗{S.RESET}"
    print(f"  {icon} {S.DIM}compiled & ran{S.RESET} {S.DIM}({elapsed:.1f}s){S.RESET}")

def print_error(msg: str):
    print(f"\n  {S.RED}● {msg}{S.RESET}\n")

def print_warning(msg: str):
    print(f"  {S.YELLOW}● {msg}{S.RESET}")

# ---------------------------------------------------------------------------
# System prompt components
# ---------------------------------------------------------------------------

AGENT_API = ""
api_path = os.path.join(SCRIPT_DIR, "AgentAPI.jo")
if os.path.exists(api_path):
    with open(api_path) as f:
        AGENT_API = f.read()

SYSTEM_PROMPT = f"""\
You are a sandboxed file-system agent. You interact with the world ONLY by
writing Jo programs, compiling them, and running them.

You have one tool:
- **runCode(code)** — writes your Jo code to a file, compiles it, and runs
  the compiled program inside the sandbox directory. Returns compiler output
  and program stdout/stderr.

Your workflow: write Jo code → runCode → if compile errors, fix and retry → report results.

## Jo Language Reference

Before writing code, read the relevant skills for language reference:
- `skills.read("jo-cheat-sheet")` — Jo syntax cheat sheet (literals, functions, classes, control flow, etc.)
- `skills.read("stdlib")` — Standard library API reference (String, List, Map, Set, Option, etc.)
- `skills.read("syntax-summary")` — Formal grammar specification (keywords, syntax rules)

On your first interaction, read these skills to learn Jo syntax and APIs.
Use `skills.grep("query")` to search skills for specific topics.

Example — read a skill and print its content:

```jo
namespace UserTask
import jo.IO.stdout
import AgentAPI.*

def runTask(): Unit receives stdout, workspace, skills, logger, actions =
  match skills.read("jo-cheat-sheet")
    case FileContent(content) => println content
    case NotFound => println "Skill not found"
    case AccessDenied(msg) => println msg
```

## Agent API (provided as context parameters)

```jo
{AGENT_API}
```

## How to Write Your Programs

Your program must follow this template:

```jo
namespace UserTask
import jo.IO.stdout
import AgentAPI.*

def runTask(): Unit receives stdout, workspace, skills, logger, actions =
  // Your code here
  // Use workspace.readFile, workspace.writeFile, workspace.appendFile, workspace.listDir,
  //     workspace.deleteFile, workspace.exists, workspace.mkdir, workspace.rename
  // Use skills.list, skills.read, skills.grep for skill lookup
  // Use logger.info, logger.warn for logging
  // Use actions.hello, actions.echo, actions.httpGet for pre-vetted actions
  // Use println for output
```

Key rules:
- Namespace must be `UserTask`
- Entry point must be `def runTask(): Unit receives stdout, workspace, skills, logger, actions`
- Use `workspace.*` methods for ALL file operations
- Use `skills.*` for reading skill definitions (read-only .md files in a hierarchy)
  - `skills.list()` returns all skill names (e.g. `["overview", "cooking/pasta", "cooking/sushi"]`)
  - `skills.read("cooking/pasta")` returns ReadResult with the skill content
  - `skills.grep("query")` searches all skills, returns `List[SearchHit]` where `SearchHit` has `.file`, `.line`, `.content`
- Use `actions.*` for pre-vetted actions (see Actions interface in the API)
  - `actions.hello("World")` returns `"Hello, World!"`
  - `actions.echo("test")` returns `"test"`
  - `actions.httpGet("https://example.com", "page.html")` fetches URL and saves to sandbox file
  - `actions.sendWhatsApp("+15551234567", "Hello!")` sends a WhatsApp message via Twilio (requires --credentials YAML file)
- Use pattern matching on ReadResult/WriteResult/ListResult for error handling
- All paths are relative to the sandbox root (use "." for root)
- Before writing a program to process or transform a file, first read the first 10-20 lines to check its format. This avoids writing code based on wrong assumptions about the file structure.
"""

# ---------------------------------------------------------------------------
# Tool definitions (Anthropic format)
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "runCode",
        "description": "Write Jo source code to a file, compile it to Python, and run it in the sandbox. Returns compiler output and program stdout/stderr.",
        "input_schema": {
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
        "-link", "jo.main=AgentRuntime.platformMain",
        "-link", "AgentAPI.runTask=UserTask.runTask",
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


def run_program(sandbox_dir: str, skills_dir: str, credentials_path: str = "") -> str:
    """Run the compiled task.py with the sandbox and skills directories."""
    cmd = ["python3", TASK_PY, os.path.abspath(sandbox_dir), os.path.abspath(skills_dir)]
    if credentials_path:
        cmd.append(os.path.abspath(credentials_path))

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


def handle_tool_call(name: str, arguments: dict, sandbox_dir: str, skills_dir: str, credentials_path: str = "") -> str:
    code = arguments.get("code", "")

    if name == "runCode":
        ok, compile_output = compile_code(code)
        if not ok:
            return compile_output
        run_output = run_program(sandbox_dir, skills_dir, credentials_path)
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

def chat_loop(sandbox_dir: str, skills_dir: str, api_key: str, model: str, credentials_path: str = ""):
    try:
        import anthropic
    except ImportError:
        print_error("anthropic package not installed. Run: pip install anthropic")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)
    init_readline()

    messages = []
    log_message({"role": "system", "content": "(system prompt)"})

    print_banner()
    print_status("Sandbox", os.path.abspath(sandbox_dir))
    print_status("Skills ", os.path.abspath(skills_dir))
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

        # Agent loop: keep going until the model produces a non-tool response
        while True:
            try:
                with Spinner(f"{S.DIM}thinking...{S.RESET}", S.MAGENTA):
                    response = client.messages.create(
                        model=model,
                        max_tokens=8096,
                        system=SYSTEM_PROMPT,
                        messages=messages,
                        tools=TOOLS,
                    )
            except KeyboardInterrupt:
                print_warning("Request cancelled.")
                break
            except Exception as e:
                import anthropic as _anthropic
                err_str = str(e)
                if isinstance(e, _anthropic.RateLimitError):
                    import re
                    match = re.search(r'try again in ([\d.]+)s', err_str)
                    wait = float(match.group(1)) if match else 5.0
                    print_warning(f"Rate limited. Retrying in {wait:.0f}s...")
                    time.sleep(wait)
                    continue
                if isinstance(e, _anthropic.APIStatusError) and e.status_code == 529:
                    print_warning("API overloaded. Retrying in 10s...")
                    time.sleep(10)
                    continue
                if isinstance(e, _anthropic.APITimeoutError) or "timed out" in err_str.lower():
                    print_warning("Request timed out. Try again or simplify your request.")
                    messages.pop()
                else:
                    print_error(f"API error: {e}")
                    messages.pop()
                break

            # Serialize content blocks to dicts for message history
            assistant_content = []
            for block in response.content:
                if block.type == "text":
                    assistant_content.append({"type": "text", "text": block.text})
                elif block.type == "tool_use":
                    assistant_content.append({
                        "type": "tool_use",
                        "id": block.id,
                        "name": block.name,
                        "input": block.input,
                    })

            messages.append({"role": "assistant", "content": assistant_content})

            # Handle tool calls
            tool_use_blocks = [b for b in response.content if b.type == "tool_use"]

            if tool_use_blocks:
                tool_results = []
                for tool_use in tool_use_blocks:
                    fn_name = tool_use.name
                    fn_args = tool_use.input

                    spinner_msg = "compiling & running..."
                    log_message({"role": "tool_call", "name": fn_name, "arguments": fn_args})

                    t0 = time.time()
                    try:
                        with Spinner(spinner_msg, S.YELLOW):
                            result = handle_tool_call(fn_name, fn_args, sandbox_dir, skills_dir, credentials_path)
                        success = "failed" not in result.lower().split('\n')[-1]
                    except Exception as e:
                        result = f"Internal error: {e}"
                        success = False
                    elapsed = time.time() - t0

                    print_tool_result(success, elapsed)
                    log_message({"role": "tool_result", "name": fn_name, "content": result})

                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": tool_use.id,
                        "content": result,
                    })

                messages.append({"role": "user", "content": tool_results})
                continue  # Loop back to get the next response

            # No tool calls — print text and break
            text_content = " ".join(
                block.text for block in response.content if block.type == "text"
            ).strip()
            if text_content:
                print(f"\n{S.BOLD}{S.CYAN}Agent ▸{S.RESET}")
                if _rich_available:
                    _rich_console.print(RichMarkdown(text_content))
                else:
                    print(text_content)
                print()
                log_message({"role": "assistant", "content": text_content})
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
        "--skills-dir", default=os.path.join(SCRIPT_DIR, "skills"),
        help="Directory containing skill .md files (default: skills)"
    )
    parser.add_argument(
        "--api-key", default=os.environ.get("ANTHROPIC_API_KEY", ""),
        help="Anthropic API key (default: $ANTHROPIC_API_KEY)"
    )
    parser.add_argument(
        "--model", default=os.environ.get("MODEL", "claude-opus-4-6"),
        help="Model name (default: $MODEL or claude-opus-4-6)"
    )
    parser.add_argument(
        "--credentials", default="",
        help="Path to YAML file with Twilio credentials for sendWhatsApp action"
    )

    args = parser.parse_args()

    if not args.api_key:
        print_error("--api-key or $ANTHROPIC_API_KEY required")
        sys.exit(1)

    os.makedirs(args.sandbox_dir, exist_ok=True)
    os.makedirs(args.skills_dir, exist_ok=True)

    if not ensure_built():
        sys.exit(1)

    chat_loop(args.sandbox_dir, args.skills_dir, args.api_key, args.model, args.credentials)


if __name__ == "__main__":
    main()
