# OpenAI Agents SDK

If you have an OpenAI Agents SDK agent, you can add a function tool that
runs Jo code. Your agent calls the tool with a Jo program and gets its output back.

We'll use a sample SQLite database of documents. Your application supplies the
current user's ID. The database runtime uses it to restrict queries, updates, and
deletes to that user's documents.

## 1. Add the bridge to your project

You need [Jo](../usage/install.md) on `PATH`, Python 3.10+, and a working OpenAI
Agents SDK application with model access. Run these commands from your existing
application's root, using its Python environment:

```bash
jo new --template typescope/jo:openai-agents-data-query jo_tools
touch jo_tools/__init__.py
python3 -m pip install -r jo_tools/requirements.txt
bash jo_tools/prepare.sh
```

This adds a Python package alongside your application:

```text
your-project/
├── agent.py                    # Your OpenAI Agents SDK application
└── jo_tools/
    ├── __init__.py
    ├── demo.py                 # Standalone CLI demo
    ├── jo_bridge.py            # run_jo() and model instructions
    ├── prepare.sh              # Build libraries and reset sample database
    ├── data/
    │   ├── DatabaseAPI.jo      # API visible to generated code
    │   ├── Runtime.jo          # Trusted implementation and entry point
    │   ├── os.jo              # Python environment bindings
    │   ├── sqlite3.jo          # Python SQLite bindings
    │   └── seed.sql            # Sample documents for users 1, 2, and 3
    └── out/                   # Prepared libraries and database.db
```

Your application imports the bridge. It does not need the standalone demo agent.

Keep `jo_bridge.py`, `data/`, and `out/` together: the bridge resolves paths relative
to its own file.

Run `prepare.sh` again after changing the Jo API or runtime, or upgrading Jo.
It also **resets the sample database**, discarding example mutations.

## 2. Verify Jo before calling a model

From your application root, call the bridge directly. This needs no API key and
exercises the same compilation and execution path as the agent tool:

```bash
python3 - <<'PY'
from jo_tools.jo_bridge import run_jo

code = """namespace docs.task
import jo.IO.stdout
import docs.api.*

def main(): Unit receives stdout, db =
  println: db.count(All)
"""

for user_id in (1, 2):
    print(run_jo(code, user_id))
PY
```

Every program must implement `docs.task.main`, the fixed entry point.
`receives stdout, db` declares its capabilities: printing and database operations.
The host supplies both. The scoped `db` lets the program access only the current
user's documents. `db.count(All)` counts those documents.

On the freshly prepared database, the output is:

```text
🔒 Running as User ID: 1

10
🔒 Running as User ID: 2

8
```

The same program sees different rows because the host supplies a different
`user_id` on each call.

## 3. Register the tool on your agent

The following adapter takes your existing agent and returns a copy with one added
tool. It assumes your agent's `instructions` are a string or `None`:

```python
from agents import Agent, Runner, function_tool

from jo_tools.jo_bridge import SYSTEM_PROMPT, run_jo


def with_jo(agent: Agent, user_id: int) -> Agent:
    @function_tool
    def run_jo_program(code: str) -> str:
        """Compile and run Jo code against this user's document database."""
        return run_jo(code, user_id)

    return agent.clone(
        instructions=(agent.instructions or "") + "\n\n" + SYSTEM_PROMPT,
        tools=[*agent.tools, run_jo_program],
    )


# Substitute the agent you already construct in your application.
base_agent = Agent(
    name="Assistant",
    model="gpt-5.6-terra",
    instructions="Help the user analyze their documents.",
)

# Demo identity. In a service, use the authenticated request's user ID.
agent = with_jo(base_agent, user_id=1)
result = Runner.run_sync(agent, "How many of my documents are drafts?")
print(result.final_output)
```

The model's tool schema contains only `code`. Create this copy for each request
with the authenticated user's ID. Do not keep a mutable global current user or
reuse another user's copy.

The adapter appends `SYSTEM_PROMPT` to your instructions. It contains the required
entry point, a Jo example, the database API source, and guidance to use the tool
for database questions and correct compilation errors before retrying.

If you use callable instructions, append `SYSTEM_PROMPT` to the text returned by
your instruction function. An async application can use `await Runner.run(...)`
with the same agent.

The official [SDK tool guide](https://openai.github.io/openai-agents-python/tools/)
describes function tools, and the [Agent reference](https://openai.github.io/openai-agents-python/ref/agent/#agents.agent.Agent.clone)
documents `clone`.

## 4. Understand what the model sends

For the draft-count question, a valid `code` argument is this complete program:

```jo
namespace docs.task
import jo.IO.stdout
import docs.api.*
import docs.api.QueryDSL.*

def main(): Unit receives stdout, db =
  println: db.count(Draft == true)
```

`docs.api.QueryDSL` provides operators such as `==` for constructing filters.
The tool result for user 1 on fresh data contains:

```text
🔒 Running as User ID: 1

4
```

The agent uses this output to answer “You have 4 draft documents.” To inspect the
exchange, log `code` and the result of `run_jo` inside the tool.

Compiler errors start with `Jo compilation failed:`. For example,
`db.missingMethod(...)` fails to compile. The agent can correct the call and retry.

Generated code cannot import the trusted runtime or Python bindings. Runtime failures
start with `Jo program failed:`.

## 5. Understand the bridge

The [bridge implementation](https://github.com/typescope/jo/blob/main/templates/openai-agents-data-query/jo_bridge.py)
writes the source to a temporary `task.jo`, then runs the equivalent of this
command from `jo_tools/`:

```bash
jo compile --python \
  --lib out/api \
  --link-lib out/runtime \
  --link jo.main=docs.runtime.platformMain \
  --link docs.api.main=docs.task.main \
  -o task.py \
  task.jo
```

`--lib` makes the confined API available while checking generated code.
`--link-lib` supplies the trusted implementation without exposing it to generated
code.

The first `--link` selects the trusted application entry point. The second
wires the API's deferred function to the generated program. The compiler checks
the linked function's signature, including its capabilities.

After successful compilation, the bridge runs `task.py` with the host-selected
user ID and database path, captures output, and returns it to the agent. Both
compilation and execution have 30-second timeouts.

The trusted runtime builds parameterized SQL and adds an `owner_id` condition to
every database operation.
See the [two-world architecture](../security/two-worlds.md) for the boundary in detail.

## Further guidance

Give the model the [Jo cheat sheet](../overview/cheat-sheet.md) and
[standard library reference](../overview/stdlib.md) through agent instructions or
retrieval when it needs more language guidance.

For API lookup, a read-only tool can run `jo compile --query jo.String`. To query
the example API from your project root, run
`jo compile --query docs.api.DB jo_tools/data/DatabaseAPI.jo`.

For faster compiler startup, see the
[native launcher instructions](../usage/install.md#pro-tip-native-launcher-for-faster-startup).

## Run the standalone demo

The scaffold also includes a CLI agent. With `OPENAI_API_KEY` configured in your
environment, run it from your application root:

```bash
python3 jo_tools/demo.py "List my documents" --user-id 1
python3 jo_tools/demo.py "List my documents" --user-id 2
```

User 1's results include “Q4 Budget Report”. User 2's include “Technical Design”.
Set `MODEL` to change the demo's model.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `Jo example is not prepared` | Run `bash jo_tools/prepare.sh` from your project root. |
| `Jo executable not found` | Install Jo and ensure the agent process has `jo` on `PATH`. |
| Libraries were built by a different Jo version | Run `bash jo_tools/prepare.sh` again after upgrading Jo. |
| Repeated compilation failures | Inspect the generated code and tool output. Check the entry-point name and that Jo guidance is included in the instructions. |
