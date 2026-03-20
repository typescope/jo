---
layout: home

hero:
  name: "Jo"
  text: "Secure Programming for the AI Era"
  tagline: A statically-typed language that enforces capability-based security at compile time — no sandboxing required.
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
    details: Control exactly what code can access via the type system. Confine AI-generated code to contracted authorities — no runtime sandboxing needed.
    link: /overview/capabilities
    linkText: Learn more

  - icon:
      src: /icons/shield-check.svg
    title: Built for AI Systems
    details: Statically prevent AI agents from accessing the network, filesystem, or unauthorized data. Compiler-enforced security boundaries.
    link: /security/security-problem
    linkText: See the problem

  - icon:
      src: /icons/sparkles.svg
    title: Pattern-Oriented Programming
    details: Define reusable pattern predicates and compose them with logical operators. Exhaustive matching with compile-time completeness checking.
    link: /overview/patterns
    linkText: Explore patterns

  - icon:
      src: /icons/badge-check.svg
    title: Type-Safe by Design
    details: A program is either rejected with helpful errors or accepted and does not crash at runtime. No null pointer exceptions, no undefined behaviour.
    link: /language/design-principles
    linkText: Design principles

  - icon:
      src: /icons/arrows.svg
    title: Context Parameters
    details: Elegant dependency injection without global variables or frameworks. Pass capabilities explicitly, test with ease.
    link: /language/concepts/context-parameters
    linkText: Learn more

  - icon:
      src: /icons/layers.svg
    title: Multiple Backends
    details: Compile to JavaScript, Ruby, or Python. Share one codebase across platforms with a consistent type-safe interface.
    link: /usage/getting-started
    linkText: Get started
---
