---
layout: home
title: Home

hero:
  name: "Jo"
  text: "For the joy of secure programming"
  tagline: Compile-time sandboxing — capabilities are explicit, fine-grained, and enforced by the compiler, not bolted on at runtime.
  actions:
    - theme: brand
      text: Get Started
      link: /usage/install
    - theme: alt
      text: Language Tour
      link: /overview/language-tour
    - theme: alt
      text: Security Model
      link: /security/security-problem

features:
  - icon:
      src: /icons/lock.svg
    title: Compile-Time Sandboxing
    details: Code can only access what it declares and is granted. Anything beyond that is rejected at compile time, before the program runs.
    link: /overview/capabilities
    linkText: Learn more

  - icon:
      src: /icons/shield-check.svg
    title: Confine Untrusted Code
    details: Structurally prevent third-party code, plugins, or generated programs from accessing anything beyond what you explicitly grant.
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
    details: One language, multiple runtimes. Compile to Python or Ruby with the same type guarantees on every platform.
    link: /usage/getting-started
    linkText: Get started
---

## From the blog

- [Why Secure AI Needs Compile-Time Sandboxing](/blog/2026-06-11-why-compile-time-sandboxing) · June 2026
- [Introducing Jo — Secure Programming for the AI Era](/blog/2026-06-04-introducing-jo) · June 2026

---

Jo is developed and maintained by [TypeScope](https://typescope.ai).
