# LangChain

If you have a LangChain agent, you can add a tool that runs Jo code.
Your agent calls the tool with a Jo program and gets its output back.

We'll use a sample SQLite database of documents. Your application supplies the
current user's ID. The database runtime uses it to restrict queries, updates, and
deletes to that user's documents.

## 1. Prepare the Jo bridge

You need [Jo](../usage/install.md) on `PATH` and Python 3.10+.
From your existing Python project's root, with its virtual environment activated:

```bash
jo new --template typescope/jo:langchain-data-query jo_tools
touch jo_tools/__init__.py
python3 -m pip install -r jo_tools/requirements.txt
bash jo_tools/prepare.sh
```

This adds a Python package alongside your application. Keep these files together:

```text
your-project/
├── agent.py                    # Your LangChain application
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
    │   └── seed.sql
    └── out/                   # Prepared libraries and database
```

The template also includes `demo.py` and a README for a standalone demo.
Your application imports the bridge. It does not need that demo agent.

`prepare.sh` builds the confined API and trusted implementation separately, then
creates `out/database.db`. Running it again resets the sample data.

## 2. Verify the bridge without a model call

Run this from your project's root. It compiles and executes the same Jo program
twice, with two host-selected identities. No API key is needed for this check.

```bash
python3 - <<'PY'
from jo_tools.jo_bridge import run_jo

code = """namespace docs.task
import jo.IO.stdout
import docs.api.*
import docs.api.QueryDSL.*

def main(): Unit receives stdout, db =
  println: db.count(All)
  println: db.count(Draft == true)
"""

for user_id in (1, 2):
    print(run_jo(code, user_id))
PY
```

With fresh sample data, the output is:

```text
🔒 Running as User ID: 1

10
4
🔒 Running as User ID: 2

8
3
```

The two numbers are the total document count and draft count. `All` and
`Draft == true` describe filters. The trusted implementation adds the current
user's `owner_id` condition to both queries.

Every generated program must implement `docs.task.main`.
`receives stdout, db` declares the capabilities it needs: printing and database
operations. The host supplies both.

Generated code cannot import the trusted runtime or Python bindings, or add
capabilities to this entry point. For example, `println(py.module("sys").argv)`
fails to compile. `run_jo` returns the error so the agent can correct its program.

## 3. Register the tool in your agent

The following is a complete LangChain adapter. Put it in your application's
`agent.py` or adapt the factory to the place where you already construct agents:

```python
from langchain.agents import create_agent
from langchain.tools import tool

from jo_tools.jo_bridge import SYSTEM_PROMPT, run_jo


def create_document_agent(
    user_id: int,
    *,
    model="openai:gpt-5.6-terra",
    tools=(),
    instructions: str | None = "You are a helpful assistant.",
    **agent_options,
):
    @tool
    def run_jo_program(code: str) -> str:
        """Compile and run Jo code against the current user's document database."""
        return run_jo(code, user_id)

    return create_agent(
        model=model,
        tools=[*tools, run_jo_program],
        system_prompt=(instructions or "") + "\n\n" + SYSTEM_PROMPT,
        **agent_options,
    )


if __name__ == "__main__":
    # Local demo identity. In a service, use the authenticated request's user ID.
    agent = create_document_agent(user_id=1)
    result = agent.invoke({
        "messages": [{
            "role": "user",
            "content": "How many documents do I have, and how many are drafts?",
        }]
    })
    print(result["messages"][-1].content)
```

Set `OPENAI_API_KEY` in your environment and run `python3 agent.py` from your
project's root. On fresh sample data, the answer should report **10 documents,
including 4 drafts**.

To inspect the generated Jo and tool output while developing, print the returned
messages before extracting the final answer:

```python
for message in result["messages"]:
    print(message)
```

A successful exchange contains an assistant tool call with Jo source, a tool
message containing the counts, then the assistant's answer. Compilation errors
appear in the tool messages. The model can call the tool again with corrected code.

The tool schema contains only `code`. The host's closure supplies `user_id`.
Create the tool for each authenticated user. Do not store the current identity in
a mutable global or share a user-bound agent between users.

The adapter appends `SYSTEM_PROMPT` from `jo_bridge.py`. It contains the required
entry point, a Jo example, the database API source, and retry instructions.

## 4. Understand what runs behind the tool

The [bridge implementation](https://github.com/typescope/jo/blob/main/templates/langchain-data-query/jo_bridge.py)
writes the generated source to a temporary `task.jo` and runs the equivalent of
this command, with paths resolved from `jo_tools/`:

```bash
jo compile --python \
  --lib out/api \
  --link-lib out/runtime \
  --link jo.main=docs.runtime.platformMain \
  --link docs.api.main=docs.task.main \
  -o task.py \
  task.jo
```

| Argument | Purpose |
| --- | --- |
| `--lib out/api` | Make the confined API visible when checking generated code. |
| `--link-lib out/runtime` | Supply the trusted implementation at link time without exposing it to generated code. |
| `--link jo.main=docs.runtime.platformMain` | Start in the trusted runtime, which opens the database and supplies capabilities. |
| `--link docs.api.main=docs.task.main` | Connect the API's required entry point to the generated function. |

If compilation succeeds, the bridge executes `task.py` using the current Python
interpreter, passing the host-selected user ID and database path. It returns stdout
or a runtime error to LangChain.

Compilation and execution each have a 30-second timeout.

The trusted runtime enforces user scoping for reads and writes. Your application
is responsible for authentication and its other tools.

See the [integration overview](index.md) and
[two-world architecture](../security/two-worlds.md) for more background.

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

With `OPENAI_API_KEY` configured, run the template's CLI agent from your project root:

```bash
python3 jo_tools/demo.py "List my documents" --user-id 1
python3 jo_tools/demo.py "List my documents" --user-id 2
```

Compare the two users' results. The demo's `MODEL` environment variable accepts
LangChain's `provider:model` format.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `Jo example is not prepared` | Run `bash jo_tools/prepare.sh` from your project root. |
| `Jo executable not found` | Install Jo and ensure the agent process has `jo` on `PATH`. |
| Libraries were built by a different Jo version | Run `bash jo_tools/prepare.sh` again after upgrading Jo. |
| Repeated compilation failures | Inspect tool messages. Check the entry-point name and that Jo guidance is included in system instructions. |
