# The AI Security Problem

Large-language models (LLMs) are extremely powerful at generating code to fulfill tasks. However, cloud platforms cannot fully embrace this power: prompt injection could generate malicious code that endangers the platform.

## Current Approaches

The following are the Current state-of-the-art solutions:

- **Container-based sandboxing** - Docker/Kubernetes process isolation
- **Language runtime sandboxing** - Deno's permission system for JavaScript
- **WebAssembly** - Sandboxed execution environment with capability-based APIs

These can achieve good confinement by disabling all capabilities from the generated program at OS or language runtime level. However, it also makes it impossible for the program to do useful things on the cloud platform.

The essential problem with the current approaches is that they only support coarse-grained authorities, which are too dangerous to be granted to user programs to perform tasks directly on the platform.

In order to do useful things, the platform has to run the program in an untrusted isolated environment with non-platform coarse-grained capability.
The program needs to go through another layer of strict authorization to do useful things indirectly on the platform (e.g. REST API).


## Fine-grained Capabilities

To address the security problem in a direct way,
we need a mechanism to define and check fine-grained capabilities. That mechanism will enable specify and check the exact application-level authorities that a user program may have.

Two language research approaches address this problem:

- **Effect systems** - Track computational effects but only support combination, not refinement
- **Object-capability languages** - More promising but face implementation challenges

## Object-Capability Languages

Object-capability languages face three fundamental implementation challenges:

**Static capability control** - Existing object-capability languages are untyped, relying on design patterns based on "connection begets connection" rather than static verification.

**Global variable design** - Global variables create ambient authorities that compromise security, yet removing them creates usability problems requiring explicit capability passing throughout programs.

**Cross-language interoperability** - Languages must interface with "lower" languages to produce effects, which eventually goes to special CPU instructions that make OS system calls and/or read/write memory-mapped registers on devices. Two implementation approaches exist:

1. Allow user code to directly cross language boundaries - Creates security auditing challenges
2. Hardcode cross-language gates as compiler intrinsics - Prevents third-party capability extensions

Cloud platforms require both clear trust boundaries for security auditing and simple cross-language interoperability to interface platform APIs.

A practical secure language should have satisfactory solutions for the three challenges above.
