# Data Query Agent

[![GitHub](https://img.shields.io/badge/GitHub-demos%2Fdata--query--agent-0969da?logo=github&logoColor=white)](https://github.com/typescope/jo/tree/main/demos/data-query-agent)

An LLM-powered agent that translates natural language prompts into Jo programs,
compiles them to Python, and executes them against a SQLite document database.

## Architecture

```
User (chat)
  ↕
agent.py (Python, Anthropic Claude API)
  ↕ tool call: runCode
  ↓
Jo program (LLM-generated) → bin/jo compile --python → python3 task.py
  ↓ uses
DatabaseAPI.jo (query DSL) + Runtime.jo (Python-backed, sqlite3)
  ↓ queries
database.db (SQLite)
```

The agent has one tool: **`runCode(code)`** — write a Jo program, compile it
to Python, and run it against the database as the current user.

Jo's type checker confines generated code to the database API. Its trusted
implementation scopes every query to the current user's `owner_id`.

The runtime ships factories for the capabilities. The compile command binds
`db` and `skills` to them with `--provide`, and binds the user ID, database
path, skills directory, and connection with `--internal`, so the compiler
creates the capabilities from the command-line arguments and calls the
generated `main` with them in scope. Generated code cannot name the internal
parameters, which live in the runtime namespace it cannot import.

## Quick Start

```bash
# Install dependencies
pip install -r requirements.txt

# Build libraries and initialize the database (done automatically on first run)
bash build.sh

# Run the agent as user 1 (Alice)
python3 agent.py --db-path database.db --user-id 1
```

Set your API key via the environment:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
python3 agent.py --db-path database.db --user-id 1
```

## Options

| Flag | Default | Description |
|------|---------|-------------|
| `--db-path` | *(required)* | Path to the SQLite database |
| `--user-id` | `1` | User ID for row-level security |
| `--api-key` | `$ANTHROPIC_API_KEY` | Anthropic API key |
| `--model` | `claude-opus-4-6` | Model to use |
| `--skills-dir` | `./skills` | Directory with skill `.md` files |

## Example Prompts

```
Show me all my documents
Find my draft documents
How many documents do I have?
Show me the 3 most recently created documents
Find documents with "Report" in the title
Mark all drafts as published
Count how many published vs draft documents I have
```

## Database Schema

```sql
CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    owner_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    draft INTEGER NOT NULL DEFAULT 0
)
```

The sample database has three users:
- User 1 (Alice): 10 documents
- User 2 (Bob): 8 documents
- User 3 (Carol): 8 documents

## Files

| File | Description |
|------|-------------|
| `DatabaseAPI.jo` | Type-safe query DSL (backend-agnostic) |
| `Runtime.jo` | Python-backed runtime with sqlite3 |
| `agent.py` | Chat loop and tool dispatch |
| `build.sh` | Compiles Jo libraries (stages 1+2) |
| `init_db.py` | Initializes the SQLite database with sample data |
| `requirements.txt` | Python dependencies |

## Generated Program Template

The LLM generates programs matching this template:

```jo
namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*

def main: Unit receives stdout, db, skills =
  // LLM-generated query/mutation code here
```

Compile command used per query:

```bash
bin/jo compile --python \
  --lib out/api \
  --link-lib out/runtime \
  --provide DatabaseAPI.db=DatabaseRuntime.DB \
  --provide DatabaseAPI.skills=DatabaseRuntime.Skills \
  --internal DatabaseRuntime.userId=DatabaseRuntime.UserId \
  --internal DatabaseRuntime.dbPath=DatabaseRuntime.DbPath \
  --internal DatabaseRuntime.skillsDir=DatabaseRuntime.SkillsDir \
  --internal DatabaseRuntime.connection=DatabaseRuntime.Connection \
  out/task.jo -o out/task.py
```
