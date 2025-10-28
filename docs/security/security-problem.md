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

So the problem is essentially how to design a language that supports fine-grained capabilities. There are two lines of language researh related to the problem:

- languages equipped with effect systems
- object-capability languages

As we have pointed out, effect systems do not fit here: effects only combine, but we want capabilities need to be refined.

The research in object-capability languages is more promising.

## Object-Capability Languages

However, there are at least three problems that object-capability languages have not well addressed.

First, the well-known object-capability languages are untyped. Security is achieved with clever patterns based on the so-called _connection begets connection_. In this setting, it is hopeless to support static control of capabilities.

Second, they do not have a design for global variables.
The presence of global variables creates security concern as they can be abused to create ambient authorities.
In contrast, if we remove global variables, there will be huage usability problem as too many capabilities will need to be passed explicitly in the program.

Third, there is not a good story for cross-language linking. In essence, a language produces side effects by calling to a "lower" language, and eventually it goes to special CPU instructions that make OS system calls and/or read/write memory-mapped registers on devices.

To make the language able to talk to a "lower" language, there are two possible choices:

(1) The language has a feature to talk to the "lower" language, which means each library or user prograrm can cross language boundary. However, this makes security auditing a nightmare.

(2) Hardcode the cross-language gates as compiler intrinsics. This means it is impossible for third-party to add new capabilities to the language.

Neither choice above are good: Cloud platforms want both _clear trust boundary_ for security auditing and _simple cross-language interoperability_ for custom platform APIs.

We claim that a practical secure language should have satisfactory solutions for the three challenges above.
