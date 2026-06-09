# Jo Secure Programming Language

Jo is a statically-typed language that enables **compile-time sandboxing**. Every side effect — IO, system access, network, filesystem — is denied by default; code can only touch what it has been explicitly granted, and the compiler proves it before the program ever runs. This is the foundation of Jo's [capability-based security](capabilities.md) model.

Jo compiles to Ruby and Python. It is possible to call Ruby/Python functions from trusted Jo code directly (See [interoperability](../guides/python-interoperability.md)).

::: warning JavaScript backend is Experimental

The JavaScript backend is currently experimental. It is only supported via raw compiler commands, not by the build tool.

:::

## Why compile-time sandboxing?

You already know **runtime sandboxes** — containers, VMs, seccomp, gVisor. They wrap a running program and police it from the outside: block this syscall, deny that path. They work, but they operate at the *wrong level*. They can stop a program from opening `/etc/passwd`, yet they cannot express "query only the rows belonging to *this* user" — that is application logic, invisible to the OS.

This is the fundamental problem Jo set out to solve:

> How do you safely execute untrusted code — with the guarantee that it only does what it is permitted to do, at any level of granularity? For example: access only a specific directory, make API requests to a single host, or query only the database rows belonging to the current user.

Jo moves the sandbox into the type system, so the boundary is checked before the program runs rather than enforced from the outside while it runs. It uses capability-contracted authority based on its type system:

- **Authority confinement** — Restrict untrusted code to exactly the granted permissions it needs, at any granularity.
- **Fine-grained control** — Scope permissions precisely: a specific directory, a single API request, or rows belonging to one user.
- **Auditable by design** — Security boundaries are visible in interface and type system, not hidden in runtime configuration.

## Design Philosophy

Jo's design philosophy is to combine strong security guarantees with programmer
happiness. Security should not require fighting the language, writing
boilerplate, or moving essential reasoning into deployment configuration. The
goal is to make secure programming feel natural, expressive, and auditable — in
short, to make secure programming a joy.

Jo is designed for both programmers and security reviewers. Capability
boundaries are expressed in interfaces and types, so the authority a program
receives is visible at the API boundary rather than scattered through
implementation details or deployment configuration. This makes security auditing
simpler: reviewers can inspect what capabilities are granted, where they flow,
and where they are deliberately restricted.

## Project Status

Jo is early-stage software, but it is already substantial: the compiler has an
extensive test suite, and the core capability model is ready for serious
experimentation. The language design, standard library, and tooling are still
evolving.

We encourage security-focused teams to evaluate Jo for new projects,
prototypes, internal tools, and constrained production use cases where existing
technologies cannot provide the authority confinement they need. For critical
deployments, start small, audit the capability boundaries carefully, and expect
the language and tooling to evolve.

## For Secure AI

Building systems that run AI-generated code? Jo's capability model lets you constrain what AI agents can access — at the type level, before anything runs. See [Capability-Based Programming](capabilities.md) for details.

For a concrete example, see the [data-query agent
demo](https://github.com/typescope/jo/tree/main/demos/data-query-agent), which
shows how an agent can ask flexible questions over a database while being
statically restricted to the current user's data.

## Formal Foundations

Jo's capability model is grounded in [λCC](https://github.com/typescope/contextual-capability), a formally verified calculus of contextual capabilities. λCC provides a mathematical account of static capability tracking and the proof is mechanized in Coq.

## Feedback and Community

We welcome feedback from language designers, security engineers, compiler
engineers, and developers building agentic systems. For concrete bugs or issues,
open an issue on [GitHub](https://github.com/typescope/jo). For community
discussion, join [r/jolang](https://www.reddit.com/r/jolang/). Security reports
should follow the process in the repository's
[SECURITY.md](https://github.com/typescope/jo/blob/main/SECURITY.md).

## Learn More

- [Language Tour](language-tour.md) - See Jo's features in action
- [Capability-Based Programming](capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](../guides/patterns.md) - Master Jo's powerful pattern system
- [Get Started](../usage/getting-started.md) - Install Jo and run your first program
