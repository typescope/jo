# Security Examples

These examples demonstrate Jo's capability system applied to security use cases.

- **[Process Monitor](process-monitor.md)** — Controlled resource access through context parameters. Shows how `receives` and `allow` make capability requirements explicit and auditable in a system monitoring tool.

- **[Data Query Agent](data-query-agent.md)** — LLM-powered agent that translates natural language into Jo programs and runs them against a SQLite database. Demonstrates row-level security enforced at the type level: AI-generated code is structurally prevented from accessing other users' data — no runtime checks needed.

- **[Sandbox Agent](sandbox-agent.md)** — LLM-powered agent restricted to a declared set of safe operations. Shows `allow none` in practice: the compiler proves the AI code uses no capabilities beyond what was explicitly granted.
