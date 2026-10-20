# Security Examples

These examples demonstrate Jo's capability system applied to security use cases. They are intentionally simple and naive — the goal is to keep the code easy to read and the security properties easy to verify, not to show production-ready applications.

To add Jo to an agent, start with [Agent framework integrations](../../integration/index.md), which runs the same data query example with LangChain, OpenAI Agents SDK, and Harpe.

- **[Process Monitor](process-monitor.md)** — Controlled resource access through context parameters. Shows how `receives` and `allow` make capability requirements explicit and auditable in a system monitoring tool.

- **[Data Query Agent](data-query-agent.md)** — LLM-powered agent that translates natural language into Jo programs and runs them against a SQLite database. The compiler prevents generated code from bypassing the confined API. The trusted runtime scopes database operations to the current user's rows.

- **[Sandbox Agent](sandbox-agent.md)** — LLM-powered agent restricted to a declared set of safe operations. Shows `allow none` in practice: the compiler proves the AI code uses no capabilities beyond what was explicitly granted.
