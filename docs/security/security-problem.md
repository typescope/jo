# The AI Security Problem

Large-language models (LLMs) are extremely powerful at generating code to fulfill tasks. However, cloud platforms cannot fully embrace this power: prompt injection could generate malicious code that endangers the platform.

## Current Approaches

The following are the Current state-of-the-art solutions:

- **Container-based sandboxing** - Docker/Kubernetes process isolation
- **Language runtime sandboxing** - Deno's permission system for JavaScript
- **WebAssembly** - Sandboxed execution environment with capability-based APIs

These can achieve good confinement by disabling all capabilities from the generated program at OS or language runtime level. However, it also makes it impossible for the program to do useful things on the cloud platform.

The essential problem with the current approaches is that they only support control of coarse-grained authorities.
The latter are too dangerous to be granted to user programs.

In order to do useful things, the platform has to run the program in an untrusted isolated environment with coarse-grained capability.
The program needs to go through another layer of authorization to do useful things indirectly on the platform (e.g. REST API).


## Fine-grained Capabilities

To address the security problem in a direct way,
we need a mechanism to define and check fine-grained capabilities. That mechanism will enable specify and check the exact application-level authorities that a user program may have.

Languages is the way to do things.
