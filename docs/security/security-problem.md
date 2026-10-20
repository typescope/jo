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

1. **Usability without ambient authorities.** Code needs access to approved effects
   without passing every capability through every function call.
2. **Security context representation.** A capability must encode whose authority it
   carries. Abstract interfaces let trusted code provide access scoped to the current
   user without exposing broader credentials or a way to forge that scope.
3. **Authority attenuation.** Trusted code must be able to give generated code a narrow
   capability without giving it a path back to the broader one. Compilation rejects
   imports and effects outside the supplied interface.

Jo is the first secure programming language to address these challenges. See
[Two-world architecture](two-worlds.md) and [Secure language design](language-design.md).
