"""Compile agent-supplied Jo against the confined data-query API."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
OUT = ROOT / "out"
API = OUT / "api"
RUNTIME = OUT / "runtime"
DATABASE = OUT / "database.db"

SYSTEM_PROMPT = f"""You answer questions about the current user's documents.
For every database question, use run_jo_program. Its input must be a complete Jo program.
The only entry point is UserTask.analyzeDocuments, with exactly these capabilities:

namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*

def analyzeDocuments(): Unit receives stdout, db =
  for doc in db.query(All) do println(doc.title)

Use db for all document operations. Print the answer with println. If compilation
fails, correct the Jo program and retry. Never claim to have accessed documents
without a successful tool result. The database API source follows:

{(DATA / 'DatabaseAPI.jo').read_text()}
"""


def run_jo(code: str, user_id: int) -> str:
    """Run generated Jo for a host-selected user. user_id is never a tool argument."""
    if not API.is_dir() or not RUNTIME.is_dir() or not DATABASE.is_file():
        return "Jo example is not prepared. Run: bash prepare.sh"
    if not code.strip() or len(code) > 50_000:
        return "Jo source must contain 1 to 50,000 characters."

    with tempfile.TemporaryDirectory(prefix="jo-agent-") as directory:
        source = Path(directory) / "task.jo"
        output = Path(directory) / "task.py"
        source.write_text(code)
        command = [
            shutil.which("jo") or "jo", "compile", "--python",
            "--link", "jo.main=DatabaseRuntime.platformMain",
            "--link", "DatabaseAPI.analyzeDocuments=UserTask.analyzeDocuments",
            "--lib", str(API), "--link-lib", str(RUNTIME),
            str(source), "-o", str(output),
        ]

        try:
            compiled = subprocess.run(
                command, capture_output=True, text=True, timeout=30, cwd=ROOT
            )
        except subprocess.TimeoutExpired:
            return "Jo compilation timed out after 30 seconds."
        except FileNotFoundError:
            return "Jo executable not found. Install Jo and add jo to PATH."
        if compiled.returncode:
            return "Jo compilation failed:\n" + (compiled.stdout + compiled.stderr).strip()

        try:
            result = subprocess.run(
                [sys.executable, str(output), str(user_id), str(DATABASE)],
                capture_output=True, text=True, timeout=30, cwd=DATA,
            )
        except subprocess.TimeoutExpired:
            return "Jo program timed out after 30 seconds."
        if result.returncode:
            return "Jo program failed:\n" + (result.stdout + result.stderr).strip()
        return result.stdout.strip() or "(no output)"
