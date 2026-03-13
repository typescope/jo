# Jo Programming Language

Jo is a statically-typed object-oriented and functional language that compiles to Ruby, Python, and JavaScript. Its type system enforces [capability-based security](overview/capabilities.md) at compile time.

## Why Jo?

Jo is designed to solve the following authority confinement problem via its
_type system_ without resorting to sandboxing nor isolation:

> How to safely execute a 3rd party function with the guarantee that it only does what
it is allowed to do, e.g., read certain rows of a database table according to access control policies, but
cannot do anything else (no abitrary http requests, file IO, etc.)?

Jo solves the security problem above in the simplest way possible without taking
away too much freedom from programmers. It brings the following benefits:

- **Authority confinement** - Confine an untrusted function to contracted authorities
- **Fine-grained control** - Attenuated authorities enable precise control, e.g. only access certain rows of a database table
- **Easy security auditing** - Compile-time checked authorities and clear security boundaries

Jo's mission is to make secure programming a joy.

## Key Features

- **Capability-based security** - Fine-grained control over what code can access, enforced by the type system
- **Pattern-oriented programming** - Define reusable pattern predicates; compose patterns with logical operators
- **Context parameters** - Elegant dependency injection without global variables or frameworks

## For Secure AI

If you are building systems that run AI-generated code, Jo provides the tools you need. See [Capability-Oriented Programming](overview/capabilities.md) for details.

## Learn More

- [Language Tour](overview/language-tour.md) - See Jo's features in action
- [Capability-Oriented Programming](overview/capabilities.md) - Deep dive into Jo's security model
- [Pattern-Oriented Programming](overview/patterns.md) - Master Jo's powerful pattern system
- [Get Started](usage/getting-started.md) - Install Jo and run your first program
