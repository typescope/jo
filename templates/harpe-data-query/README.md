# Harpe data query agent

This template connects a Harpe agent to a confined Jo database API. It is
copied as a standalone project with
`jo new --template typescope/jo:harpe-data-query my-agent`.

Install Jo and put `jo` on `PATH`. Then, from the copied project:

```bash
bash prepare.sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY="your-api-key"
JO_USER_ID=1 MODEL=gpt-5.6-terra jo run
```

Ask “List my documents.” Run again with `JO_USER_ID=2` to compare results.
`prepare.sh` copies the confined API and trusted runtime into the guest
module, builds it, and creates a disposable database in `out/` from
`data/seed.sql`.
The Harpe driver supplies the user ID outside the model's tool input. In a
deployed application, derive it from the authenticated request.

The sample API allows user-scoped queries and mutations. Narrow it if your
agent should only read. See the [integration guide](https://jo-lang.org/integration/harpe)
for the architecture and code walkthrough.
