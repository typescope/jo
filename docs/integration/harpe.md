# Harpe

[Harpe](https://harpe.jo-lang.org/) includes `RunCodeTool` for compiling and running
Jo code. This example connects it to a SQLite database scoped to the current user.

## The sandbox

The `sandbox/` directory is a Jo project. Its `jo.toml` defines how to build it:

```text
sandbox/
├── jo.toml          # Defines the three modules and their links
├── DatabaseAPI.jo   # docs.api: API visible to generated code
├── runtime/
│   ├── Runtime.jo   # docs.runtime: trusted implementation
│   ├── os.jo        # Python bindings used by the runtime
│   └── sqlite3.jo
├── Task.jo          # Build-time guest placeholder
└── run.sh           # Launches the compiled guest
```

The three modules have separate roles:

- `api` exposes `DatabaseAPI.jo` as a pure library for generated code.
- `runtime` builds the trusted implementation in `runtime/` with Python FFI enabled.
- `guest` checks `Task.jo` against `api`, then links it with `runtime`.

Here's `sandbox/jo.toml`:

```toml
jo = "0.14"

[module.api]
kind = "lib"
platform = "pure"
src = ["DatabaseAPI.jo"]

[module.runtime]
kind = "lib"
platform = "python"
enable-ffi = true
src = ["runtime/"]
modules = ["api"]

[module.guest]
kind = "app"
platform = "python"
src = ["Task.jo"]
modules = ["api", { id = "runtime", link = true }]

links = [
  { from = "jo.main", to = "docs.runtime.platformMain" },
  { from = "docs.api.main", to = "docs.task.main" },
]
```

For more background, see the [integration overview](index.md),
[two-world architecture](../security/two-worlds.md), and Harpe's
[sandbox guide](https://harpe.jo-lang.org/concepts/sandbox/).

## The compile-and-run tool

The [driver](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/src/Main.jo)
registers `runCode` and supplies the user ID and database path:

```jo
val sandbox = os.path.abspath("sandbox")
val userId = os.getenv("JO_USER_ID", "1")
val runCode = RunCodeTool(sandbox, "guest", approvalDeadline = 610)
val guestEnv = Map:
  "HARPE_USER_ID" ~ userId
  "HARPE_DB_PATH" ~ os.path.abspath("out/database.db")

val tools = runCode.toolset(guestEnv)
```

The `"guest"` argument to `RunCodeTool` selects the application module in
`sandbox/jo.toml`.

When your agent calls `runCode`:

1. `RunCodeTool` compiles the program against the prepared guest module. Compiler
   errors go back to the agent so it can correct the code.
2. On success, the [sandbox runner](https://github.com/typescope/jo/blob/main/templates/harpe-data-query/sandbox/run.sh)
   executes the program with the user ID and database path supplied by the driver.

The runtime adds the current user's `owner_id` filter to each database operation.
The model supplies only Jo code and cannot choose the user identity.

The CLI uses `JO_USER_ID` as a sample identity. In a service, use the authenticated
request's user ID. Your application remains responsible for authentication and its
other tools.

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

`prepare.sh` builds the sandbox modules and creates `out/database.db` from
`data/seed.sql`. Run it again to reset the sample data.

## Further guidance

Give the model the [Jo cheat sheet](../overview/cheat-sheet.md) and
[standard library reference](../overview/stdlib.md) as skills.

For API lookup, a read-only tool can run `jo compile --query jo.String`. To query
the example API, run `jo compile --query docs.api.DB sandbox/DatabaseAPI.jo`.

For faster compiler startup, see the
[native launcher instructions](../usage/install.md#pro-tip-native-launcher-for-faster-startup).
