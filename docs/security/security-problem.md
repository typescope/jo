# The AI Security Problem

Large-language models (LLMs) are extremely powerful at generating code to automate tasks. However, cloud platforms cannot fully embrace this power: prompt injection could generate malicious code that endangers the platform.

## Ambient Authorities

Nearly all popular languages expose powerful ambient authorities to programs:

- Network access
- File system read/write

With such powerful authorities, a program can potentially do anything.
A malicious program with such authorities can steal all the data and hijack the platform.

## The API Confinement Problem

What we really want is to confine the behaviors of a program according to specific _security context_.
For cloud platforms, the security context at least contains the current user.
The LLM-generated programs for the user should be at least confined to the current user's permissions on the platform.

It is easy for a platform to specify and implement security-context-aware APIs as a library in a language.
However, none of the popular languages can enforce that a program is confined to the permitted APIs.

In fact, the API confiment problem is a language design challenge:

- How to represent the security context such that it is invisible to user programs while visible in platform API implementation?
- How to make the ambient authorities inaccessible to user programs while accessible to API implementation?

<!-- Lampson ([1973](https://doi.org/10.1145/362375.362389)) identified a fundamental challenge: confining a program during execution so that it cannot transmit information to any other program except only to its caller. The difficulty arises because: -->

<!-- - Programs need some access to be useful, but any access can potentially be misused -->
<!-- - Covert channels (timing, storage, legitimate outputs) can leak information indirectly -->
<!-- - Runtime enforcement mechanisms are complex and difficult to verify completely -->
<!-- - Perfect confinement conflicts with program functionality -->



## Current Approaches

The following are the current state-of-the-art solutions:

- **Container-based sandboxing** - Docker/Kubernetes process isolation
- **Language runtime sandboxing** - Deno's permission system for JavaScript
- **WebAssembly** - Sandboxed execution environment with capability-based APIs

These can achieve good confinement by disabling all capabilities from the generated program at OS or language runtime level. However, it also makes it impossible for the program to do useful things on the cloud platform.

The essential problem with the current approaches is that they only support coarse-grained authorities, which are too dangerous to be granted to user programs to perform tasks directly on the platform.

In order to do useful things, the platform has to run the program in an untrusted isolated environment with non-platform coarse-grained authority.
The program needs to go through another layer of strict authorization to do useful things indirectly on the platform (e.g. REST API).
In essence, we are workarounding a language design problem by resorting to a system solution.

<!-- ## Capability-based Systems -->

<!-- Capability-based systems partially address the confinement problem by controlling resource access. -->

<!-- **What capabilities can do:** -->

<!-- - Prevent unauthorized resource access through explicit capability provision -->
<!-- - Eliminate ambient authority that makes access difficult to track -->
<!-- - Enable static verification of resource access through program interfaces -->
<!-- - Provide fine-grained, composable access control -->

<!-- **What capabilities cannot do:** -->

<!-- - Prevent covert channels (timing, resource usage, cache behavior, power consumption) -->
<!-- - Prevent information leakage through legitimate program outputs -->

<!-- Capabilities improve security by making access explicit and verifiable, but the theoretical limits identified by Lampson remain. -->


## Fine-Grained Authority

To address the security problem in a direct way,
we need a mechanism to define and check fine-grained authority. That mechanism will enable specify and check the exact application-level authorities that a user program may have.

Two language research approaches are related to this problem:

- **Effect systems**
- **Object-capability languages**

Effect systems enable check side effects of a function statically, which can be used to control authorities that a function needs. However, an inherent property of effects is that they only compose but cannot be refined. Otherwise, it will be impossible to tell whether a piece of code is effect-free or not -- the primary goal of effect systems.  Therefore, effect systems cannot be used as a mechanism to create and check fine-grained authority.

The research on object-capability languages is more promising because capabilities can be both combined and refined.

## Object-Capability Languages

Object-capability languages face three fundamental implementation challenges:

**Static capability control** - Existing object-capability languages are untyped, relying on design patterns based on "connection begets connection" rather than static verification.

**Global variable design** - Global variables create ambient authorities that compromise security, yet removing them creates usability problems requiring explicit capability passing throughout programs.

**Cross-language interoperability** - Languages must interface with "lower" languages to produce effects, which eventually goes to special CPU instructions that make OS system calls and/or read/write memory-mapped registers on devices. Two implementation approaches exist:

1. Allow user code to directly cross language boundaries - Creates security auditing challenges
2. Hardcode cross-language gates as compiler intrinsics - Prevents third-party capability extensions

Cloud platforms require both clear trust boundaries for security auditing and simple cross-language interoperability to interface platform APIs.

A practical secure language should have satisfactory solutions for the three challenges above.
