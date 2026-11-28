# Harpe

[Harpe](https://harpe.jo-lang.org/) includes `RunCodeTool` for compiling and running
Jo code. This example connects it to a SQLite database scoped to the current user.

## The compile-and-run tool

The [driver](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/src/Main.jo)
registers `runCode` and supplies the user ID and database path:

```jo
val home = os.path.abspath(".")
val userId = os.getenv("JO_USER_ID", "1")
val runCode = RunCodeTool(os.path.join(home, "sandbox"), "guest", approvalDeadline = 610)
val guestEnv = Map:
  "HARPE_USER_ID" ~ userId
  "HARPE_DB_PATH" ~ os.path.abspath("out/database.db")

val tools = runCode.toolset(guestEnv)
```

When your agent calls `runCode`:

1. `RunCodeTool` compiles the program against the prepared guest module. Compiler
   errors go back to the agent so it can correct the code.
2. On success, the [sandbox runner](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/sandbox/run.sh)
   executes the program with the user ID and database path supplied by the driver.

Generated code can use `docs.api`, the standard library, and the capabilities
declared by `docs.api.main`. It cannot import the trusted runtime.

The runtime adds the current user's `owner_id` filter to each database operation.
The model supplies only Jo code and cannot choose the user identity.

The CLI uses `JO_USER_ID` as a sample identity. In a service, use the authenticated
request's user ID. Your application remains responsible for authentication and its
other tools.

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
documents for users 1, 2, and 3.

`prepare.sh` copies the API, runtime, and Python bindings into `sandbox/`, then
builds the guest module. It creates `out/database.db` from `data/seed.sql`. Run it
again to reset the sample data.

## Further guidance

Give the model the [Jo cheat sheet](../overview/cheat-sheet.md) and
[standard library reference](../overview/stdlib.md) as skills.

For API lookup, a read-only tool can run `jo compile --query jo.String`. To query
the example API, run `jo compile --query docs.api.DB data/DatabaseAPI.jo`.

For faster compiler startup, see the
[native launcher instructions](../usage/install.md#pro-tip-native-launcher-for-faster-startup).
