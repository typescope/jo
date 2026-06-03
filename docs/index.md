---
layout: home
title: Home

hero:
  name: "Jo"
  text: "Secure Programming for the AI Era"
  tagline: Give AI agents only the capabilities they need — guaranteed by the type system, not a runtime sandbox.
  actions:
    - theme: brand
      text: Get Started
      link: /usage/getting-started
    - theme: alt
      text: Language Tour
      link: /overview/language-tour
    - theme: alt
      text: AI Security
      link: /security/security-problem

features:
  - icon:
      src: /icons/lock.svg
    title: Capability-Based Security
    details: Code can only access what it declares and is granted. Anything beyond that is rejected as compiling errors.
    link: /overview/capabilities
    linkText: Learn more

  - icon:
      src: /icons/shield-check.svg
    title: Built for AI Systems
    details: Confine AI agents to declared capabilities. No network, filesystem, or data access beyond what you explicitly allow.
    link: /security/security-problem
    linkText: See the problem

  - icon:
      src: /icons/arrows.svg
    title: Context Parameters
    details: Pass capabilities through the call stack implicitly — no boilerplate, no globals, no frameworks.
    link: /guides/context-parameters
    linkText: Learn more

  - icon:
      src: /icons/badge-check.svg
    title: Type-Safe by Design
    details: No runtime type errors, the compiler catches what other languages let slip through.
    link: /language/design-principles
    linkText: Design principles

  - icon:
      src: /icons/sparkles.svg
    title: Pattern-Oriented Programming
    details: Compose reusable pattern predicates with logical operators. Exhaustive matching — the compiler guarantees no case is missed.
    link: /guides/patterns
    linkText: Explore patterns

  - icon:
      src: /icons/layers.svg
    title: Multiple Backends
    details: One language, multiple runtimes. Compile to Python, JavaScript, or Ruby with the same type guarantees on every platform.
    link: /usage/getting-started
    linkText: Get started
---

Jo is developed and maintained by [TypeScope](https://typescope.ai).
