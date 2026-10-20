# Popular Languages Are Unsafe for AI

When you let an AI agent write and run code to fulfill tasks, popular languages do not
provide the mechanism to specify and enforce what the code can do.

## Why existing languages are unsafe

File I/O, environment variables, network access, subprocesses, and FFI are **ambient
authorities**: code can reach them without receiving an explicit capability from its
caller. A function meant to use a scoped API can also read a file accessible to the
host process:

```python
documents = document_api.list_my_documents()

from pathlib import Path
secret = Path("/srv/app/secrets/service-token").read_text()
```

OS isolation can block access to files and networks, but it cannot by itself enforce
application rules such as "query only this user's rows."
[Compile-time sandboxing](../overview/capabilities.md) within a language restricts
generated code to a scoped API whose trusted implementation enforces those rules.

## Three challenges

Securing AI-generated code at the language level requires solving three problems.

1. **Usability without ambient authorities.** A secure language must remove ambient
   authorities. Done naively, that means threading every capability through every
   function call. The language needs to pass authorities implicitly while keeping them
   statically trackable, so the common case stays ergonomic and every authority stays
   auditable.
2. **Security context representation.** The authority given to generated code must
   encode the current user: not just "database access" but "this user's rows only."
   Generated code must not be able to inspect, forge, or amplify that context. The
   language needs a way to represent it and a type system that enforces the
   abstraction boundary.
3. **Authority attenuation.** Trusted code holds broad authorities. Generated code
   should receive only the narrow slice it needs. The language must derive
   fine-grained authorities from coarse ones and guarantee that the coarse authority
   cannot be recovered through the derived one. Reflection and object inspection, as
   in Java and JavaScript, break this guarantee.

Jo is the first secure programming language to address these challenges. See
[Two-world architecture](two-worlds.md) and [Secure language design](language-design.md).
