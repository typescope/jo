# Jo Programming Language

Jo is a statically-typed language where all side effects — IO, system access, network, filesystem — are denied by default. Any capability must be explicitly declared and granted through contracted interfaces, enforced at compile time. This is the foundation of Jo's [capability-based security](capabilities.md) model.

## Why Jo?

It's a fundamental problem in secure software:

> How do you safely execute untrusted code — with the guarantee that it only does what it is permitted to do, at any level of granularity? For example: access only a specific directory, make API requests to a single host, or query only the database rows belonging to the current user.

Jo solves the problem by using capability contracted authority based on its type system:

- **Authority confinement** — Restrict untrusted code to exactly the granted permissions it needs, at any granularity.
- **Fine-grained control** — Scope permissions precisely: a specific directory, a single API request, or rows belonging to one user.
- **Auditable by design** — Security boundaries are visible in interface and type system, not hidden in runtime configuration.

## For Secure AI

Building systems that run AI-generated code? Jo's capability model lets you constrain what AI agents can access — at the type level, before anything runs. See [Capability-Oriented Programming](capabilities.md) for details.

## Learn More

- [Language Tour](language-tour.md) - See Jo's features in action
- [Capability-Oriented Programming](capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](patterns.md) - Master Jo's powerful pattern system
- [Get Started](../usage/getting-started.md) - Install Jo and run your first program
