# Harpe

[Harpe](https://harpe.jo-lang.org/) integrates with Jo through its built-in `RunCodeTool`,
which compiles and runs model-generated Jo programs. This example uses it to query a
user-scoped SQLite database.

## The compile-and-run tool

The [Harpe driver](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/src/Main.jo) gives the model `runCode` and supplies the user ID and database path as trusted per-run configuration:

```jo
val home = os.path.abspath(".")
val userId = os.getenv("JO_USER_ID", "1")
val runCode = RunCodeTool(os.path.join(home, "sandbox"), approvalDeadline = 610)
val guestEnv = Map:
  "HARPE_USER_ID" ~ userId
  "HARPE_DB_PATH" ~ os.path.abspath("out/database.db")

val tools = runCode.toolset(guestEnv)
```

`approvalDeadline` is a watchdog, in seconds, for generated code that waits on a
[human approval](https://harpe.jo-lang.org/concepts/approvals/). This example does
not request approvals.

When the model calls `runCode`, `RunCodeTool` compiles its Jo source against the prepared
guest module and returns compiler errors for revision. On success, the
[sandbox runner](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/sandbox/run.sh)
executes the compiled program with the user ID and database path supplied
by the driver. The generated program can use only `DatabaseAPI.jo`, the standard
library, and the capabilities in the entry point's signature. The compiler rejects
everything else.
The trusted runtime adds the current user's `owner_id` condition to database operations.
The model controls only the Jo source passed to `runCode`. The CLI environment variable
stands in for an authenticated identity. In a service, derive the user ID from the
authenticated request. 
outside this Jo boundary.

For more background, see the [integration overview](index.md),
[two-world architecture](../security/two-worlds.md), and Harpe's
[sandbox guide](https://harpe.jo-lang.org/concepts/sandbox/).

## Run the example

Install [Jo](../usage/install.md), then create and enter the project:

```bash
# Create the project
jo new --template typescope/jo:harpe-data-query my-agent
cd my-agent

# Prepare the guest module and sample database
bash prepare.sh

# Install Python dependencies
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Run the agent
export OPENAI_API_KEY="your-api-key"
JO_USER_ID=1 MODEL=gpt-5.6-terra jo run
```

Ask “List my documents.” Run the same question with
`JO_USER_ID=2 MODEL=gpt-5.6-terra jo run` to compare results. The sample database has
documents for users 1, 2, and 3. `prepare.sh` copies the confined
`data/DatabaseAPI.jo`, the trusted `data/Runtime.jo`, and its `os.jo` and `sqlite3.jo`
bindings into the guest module, builds it, and refreshes the database in
`out/`. Run it again to reset any changes made by the agent.

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
