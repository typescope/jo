# OpenAI Agents SDK

Add Jo to the OpenAI Agents SDK  with one function tool that compiles and runs
model-generated Jo programs. This example uses the tool to query a user-scoped SQLite database.

## The compile-and-run tool

The [OpenAI Agents SDK example](https://github.com/typescope/jo/blob/main/templates/openai-agents-data-query/agent.py) exposes the bridge as a function tool:

```python
@function_tool
def run_jo_program(code: str) -> str:
    """Compile and run Jo code against this user's document database."""
    return run_jo(code, args.user_id)

agent = Agent(
    name="Document analyst",
    instructions=SYSTEM_PROMPT,
    model=os.environ.get("MODEL", "gpt-5.6-terra"),
    tools=[run_jo_program],
)
result = Runner.run_sync(agent, args.prompt)
```

Only `code` is a model tool argument. The Python host chooses `user_id` with the CLI
flag in this example. In a service, derive it from the authenticated request. 
The [OpenAI Agents SDK quickstart](https://developers.openai.com/api/docs/guides/agents/quickstart)
documents this function tool pattern.

## What the Jo bridge does

The [Jo bridge](https://github.com/typescope/jo/blob/main/templates/openai-agents-data-query/jo_bridge.py)
implements `run_jo(code, user_id)` behind the tool:

1. Write the model's Jo source to a temporary file.
2. Run `jo compile --python` against the confined API (`--lib`) and link in the
   trusted runtime (`--link-lib`). Return compiler errors to the agent so it can
   revise the source.
3. Run the compiled Python program in a minimal environment with the host-selected
   user ID and database path. Return its output or runtime error to the agent.

The generated program can use only the API, the standard library, and the
capabilities in the entry point's signature. The compiler rejects everything else.
The trusted runtime adds the current user's `owner_id` condition to database operations.

For more background, see the [integration overview](index.md) and
[two-world architecture](../security/two-worlds.md).

## Run the example

Install [Jo](../usage/install.md), then create and enter the project:

```bash
# Create the project
jo new --template typescope/jo:openai-agents-data-query my-agent
cd my-agent

# Prepare the Jo libraries and sample database
bash prepare.sh

# Install Python dependencies
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Run the agent
export OPENAI_API_KEY="your-api-key"
python3 agent.py "List my documents" --user-id 1
```

Run the same question with `--user-id 2` to compare results. Set `MODEL` to choose
another model. The sample database has
documents for users 1, 2, and 3. `prepare.sh` compiles the confined
`data/DatabaseAPI.jo` and trusted `data/Runtime.jo`, then copies the sample database
to `out/`. Run it again to reset any changes made by the agent.

## For production

Give the model a Jo coding skill based on the
[cheat sheet](https://github.com/typescope/jo/blob/main/docs/overview/cheat-sheet.md)
and [standard library reference](https://github.com/typescope/jo/blob/main/docs/overview/stdlib.md).
Make that guidance available through agent instructions or retrieval before the model
writes code. You can also expose `jo compile --query jo.String` as a read-only tool
for looking up API signatures and documentation. To query the confined API, pass its
source file, for example `jo compile --query DatabaseAPI.DB data/DatabaseAPI.jo`.

To reduce Jo's cold-start time, compile it to a native image as described in the
[install guide](../usage/install.md#pro-tip-native-launcher-for-faster-startup). A
compilation server offers the best performance. Contact us for support.
