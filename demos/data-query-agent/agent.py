#!/usr/bin/env python3
"""
Data Query Agent — an LLM-powered agent that translates natural language
into Jo programs, compiles them to Python, and executes them against a
SQLite document database.

The agent has one tool:
  runCode(code) — write a Jo program, compile to Python, and run it
                  against the configured database.
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
    CYAN    = "\033[36m"
    GREEN   = "\033[32m"
    YELLOW  = "\033[33m"
    RED     = "\033[31m"
    MAGENTA = "\033[35m"
    BLUE    = "\033[34m"
    WHITE   = "\033[37m"

    @staticmethod
    def enabled():
        return hasattr(sys.stdout, 'isatty') and sys.stdout.isatty()

if not Style.enabled():
    for attr in ['RESET', 'BOLD', 'DIM', 'CYAN', 'GREEN', 'YELLOW',
                 'RED', 'MAGENTA', 'BLUE', 'WHITE']:
        setattr(Style, attr, '')

S = Style

class Spinner:
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
        print(f"\r\033[2K", end="")


def print_banner():
    print(f"""
{S.CYAN}{S.BOLD}
  ╔═══════════════════════════════════════╗
  ║       Jo Data Query Agent             ║
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
# System prompt
# ---------------------------------------------------------------------------

DATABASE_API_PATH = os.path.join(SCRIPT_DIR, "DatabaseAPI.jo")
DATABASE_API = ""
if os.path.exists(DATABASE_API_PATH):
    with open(DATABASE_API_PATH) as f:
        DATABASE_API = f.read()

def load_skills(skills_dir: str) -> dict[str, str]:
    """Load all .md skill files from skills_dir into a dict keyed by stem."""
    skills = {}
    if not os.path.isdir(skills_dir):
        return skills
    for fname in os.listdir(skills_dir):
        if fname.endswith(".md"):
            stem = fname[:-3]
            with open(os.path.join(skills_dir, fname)) as f:
                skills[stem] = f.read()
    return skills


def build_system_prompt(skills: dict[str, str]) -> str:
    cheat_sheet = skills.get("jo-cheat-sheet", "(not found)")
    syntax_summary = skills.get("syntax-summary", "(not found)")
    database_api_skill = skills.get("database-api", "(not found)")

    return f"""\
You are a database query agent. You interact with a SQLite document database ONLY by
writing Jo programs, compiling them to Python, and running them.

You have one tool:
- **runCode(code)** — writes your Jo code to a file, compiles it to Python, and runs it
  against the configured database as the current user. Returns compiler output and
  program stdout/stderr.

Your workflow: write Jo code → runCode → if compile errors, fix and retry → report results.

## Database API

The database contains a `documents` table with these columns:
- `id` (Int): primary key
- `title` (String): document title
- `content` (String): document body
- `ownerId` (Int): owner's user ID (all queries auto-scoped to current user)
- `createdAt` (String): creation date, format "YYYY-MM-DD"
- `draft` (Bool): true if document is a draft

All queries are automatically scoped to the current user via row-level security.

## DatabaseAPI Reference

{database_api_skill}

## DatabaseAPI.jo (full source for reference)

```jo
{DATABASE_API}
```

## How to Write Your Programs

Your program **must** follow this exact template:

```jo
namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*

def analyzeDocuments(): Unit receives stdout, db =
  // Your code here — use db.query, db.count, db.getById,
  // db.update, db.updateById, db.delete, db.deleteById
  // Use println to output results
```

Key rules:
- Namespace must be `UserTask`
- Entry point must be `def analyzeDocuments(): Unit receives stdout, db`
- Use `db.*` methods for ALL database operations
- Use `println` to display results to the user
- String concatenation with `+` requires all parts to be String — use `.toString` on non-strings
  or just write `"value: " + someInt` (Int auto-converts in string context)
- Pattern match on `Option[Document]` with `case Some(doc)` / `case None`
- Iterate over lists with `for doc in docs do ... `

## Jo Language Quick Reference

{cheat_sheet}

## Formal Syntax Summary

{syntax_summary}
"""

# ---------------------------------------------------------------------------
# Tool definitions (Anthropic format)
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "runCode",
        "description": "Write Jo source code to a file, compile it to Python, and run it against the database as the current user. Returns compiler output and program stdout/stderr.",
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
        "-link", "jo.main=DatabaseRuntime.platformMain",
        "-link", "DatabaseAPI.analyzeDocuments=UserTask.analyzeDocuments",
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


def run_program(user_id: int, db_path: str) -> str:
    """Run the compiled task.py with the given userId and database path."""
    cmd = ["python3", TASK_PY, str(user_id), os.path.abspath(db_path)]

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


def handle_tool_call(name: str, arguments: dict, user_id: int, db_path: str) -> str:
    code = arguments.get("code", "")

    if name == "runCode":
        ok, compile_output = compile_code(code)
        if not ok:
            return compile_output
        run_output = run_program(user_id, db_path)
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
    os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
    entry["timestamp"] = datetime.datetime.now().isoformat()
    with open(LOG_FILE, "a") as f:
        f.write(json.dumps(entry, default=str) + "\n")

# ---------------------------------------------------------------------------
# Chat loop
# ---------------------------------------------------------------------------

def chat_loop(user_id: int, db_path: str, api_key: str, model: str, skills_dir: str):
    try:
        import anthropic
    except ImportError:
        print_error("anthropic package not installed. Run: pip install anthropic")
        sys.exit(1)

    skills = load_skills(os.path.normpath(skills_dir))
    system_prompt = build_system_prompt(skills)

    client = anthropic.Anthropic(api_key=api_key)
    init_readline()

    messages = []
    log_message({"role": "system", "content": "(system prompt)"})

    print_banner()
    print_status("Database", os.path.abspath(db_path))
    print_status("User ID ", str(user_id))
    print_status("Model   ", model)
    print_status("Log     ", LOG_FILE)
    print(f"\n  {S.DIM}Type your request (Ctrl+D or 'quit' to exit){S.RESET}\n")
    print(f"  {S.DIM}{'─' * 40}{S.RESET}\n")

    while True:
        try:
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

        while True:
            try:
                with Spinner(f"{S.DIM}thinking...{S.RESET}", S.MAGENTA):
                    response = client.messages.create(
                        model=model,
                        max_tokens=8096,
                        system=system_prompt,
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

            tool_use_blocks = [b for b in response.content if b.type == "tool_use"]

            if tool_use_blocks:
                tool_results = []
                for tool_use in tool_use_blocks:
                    fn_name = tool_use.name
                    fn_args = tool_use.input

                    log_message({"role": "tool_call", "name": fn_name, "arguments": fn_args})

                    t0 = time.time()
                    try:
                        with Spinner("compiling & running...", S.YELLOW):
                            result = handle_tool_call(fn_name, fn_args, user_id, db_path)
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
                continue

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
        description="Data query agent — LLM writes Jo programs to query a SQLite document database"
    )
    parser.add_argument(
        "--db-path", required=True,
        help="Path to the SQLite database file"
    )
    parser.add_argument(
        "--user-id", type=int, default=1,
        help="User ID for row-level security (default: 1)"
    )
    parser.add_argument(
        "--skills-dir", default=os.path.join(SCRIPT_DIR, "..", "skills"),
        help="Directory containing skill .md files (default: ../skills)"
    )
    parser.add_argument(
        "--api-key", default=os.environ.get("ANTHROPIC_API_KEY", ""),
        help="Anthropic API key (default: $ANTHROPIC_API_KEY)"
    )
    parser.add_argument(
        "--model", default=os.environ.get("MODEL", "claude-opus-4-6"),
        help="Model name (default: $MODEL or claude-opus-4-6)"
    )

    args = parser.parse_args()

    if not args.api_key:
        print_error("--api-key or $ANTHROPIC_API_KEY required")
        sys.exit(1)

    if not ensure_built():
        sys.exit(1)

    chat_loop(args.user_id, args.db_path, args.api_key, args.model, args.skills_dir)


if __name__ == "__main__":
    main()
