# The AI Security Problem

Large-language models (LLMs) are extremely powerful at generating code to automate tasks. However, cloud platforms cannot fully embrace this power: through _prompt injection_ a user can trick the LLM to generate malicious code that endangers the platform.

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

## The Authority Confinement Problem

What we really want is to confine the behaviors of a LLM-generated program according to specific _security context_.
For cloud platforms, the security context at least contains the current user.
The LLM-generated programs for the user should be at least confined to the current user's permissions on the platform.

It is easy for a platform to specify and implement security-context-aware APIs as a library in a language.
However, none of the popular languages can enforce that an untrusted program is confined to the APIs due to the ubiquity of ambient authorities.

In fact, the authority confiment problem is a language design challenge:

- How to remove all ambient authorities (any backdoor breaks security) and still make the language easy to use?
- How to represent the security context such that it is invisible to untrusted program while visible in the trusted API implementation?
- How to make the powerful authorities inaccessible to untrusted programs while accessible to trusted API implementation?


<!-- Lampson ([1973](https://doi.org/10.1145/362375.362389)) identified a fundamental challenge: confining a program during execution so that it cannot transmit information to any other program except only to its caller. The difficulty arises because: -->

<!-- - Programs need some access to be useful, but any access can potentially be misused -->
<!-- - Covert channels (timing, storage, legitimate outputs) can leak information indirectly -->
<!-- - Runtime enforcement mechanisms are complex and difficult to verify completely -->
<!-- - Perfect confinement conflicts with program functionality -->

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

<!-- Two language research approaches are related to this problem: -->

<!-- - **Effect systems** -->
<!-- - **Object-capability languages** -->

<!-- Effect systems enable check side effects of a function statically, which can be used to control authorities that a function needs. However, an inherent property of effects is that they only compose but cannot be refined. Otherwise, it will be impossible to tell whether a piece of code is effect-free or not -- the primary goal of effect systems.  Therefore, effect systems cannot be used as a mechanism to create and check fine-grained authority. -->

<!-- The research on object-capability languages is more promising because capabilities can be both combined and refined. -->

<!-- ## Object-Capability Languages -->

<!-- Object-capability languages face three fundamental implementation challenges: -->

<!-- **Static capability control** - Existing object-capability languages do not check capabilities statically, relying on design patterns based on "connection begets connection" rather than static verification. -->

<!-- **Global variable design** - Global variables create ambient authorities that compromise reasoning about security, yet removing them creates usability problems requiring explicit capability passing throughout programs. -->

<!-- **Cross-language interoperability** - Languages must interface with "lower" languages to produce effects, which eventually goes to special CPU instructions that make OS system calls and/or read/write memory-mapped registers on devices. Two implementation approaches exist: -->

<!-- 1. Allow user code to directly cross language boundaries through FFI (foreign function interface) - Creates security auditing challenges -->
<!-- 2. Hardcode cross-language gates as compiler intrinsics - Prevents third-party capability extensions -->

<!-- Cloud platforms require both clear trust boundaries for security auditing and simple cross-language interoperability to interface platform APIs. -->

<!-- A practical secure language should have satisfactory solutions for the three challenges above. -->
