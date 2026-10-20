# LangChain data query agent

This template connects a LangChain agent to a confined Jo database API. It is
copied as a standalone project with `jo new --template typescope/jo:langchain-data-query my-agent`.

Install Jo and put `jo` on `PATH`. Then, from the copied project:

```bash
bash prepare.sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY="your-api-key"
python3 agent.py "List my documents" --user-id 1
```

Run again with `--user-id 2` to see another user's documents. `prepare.sh`
compiles the confined `data/DatabaseAPI.jo` and trusted `data/Runtime.jo`,
and refreshes a disposable copy of the database in `out/`. The agent supplies
Jo source to a single tool; the Python host supplies the user ID outside the
tool schema. In a deployed application, derive that identity from the
authenticated request. The host and its other tools remain outside Jo's
generated-code boundary.

The sample API allows user-scoped queries and mutations. Narrow it if your
agent should only read. See the [integration guide](https://jo-lang.org/integration/langchain)
for the architecture and code walkthrough.
