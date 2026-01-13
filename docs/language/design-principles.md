# Design Principles

This document outlines the core design principles that guide Jo's language design decisions.

## 1. Local Reasoning

**Principle:** The behavior of code should be understandable by examining the code locally, without needing to search through distant parts of the codebase.

!!!info "Why This Matters"

    Many languages with implicit features (Scala implicits, Haskell type classes, Swift extensions, Rust traits) require searching through imports and lexical scope to determine what methods are available on a value. This breaks local reasoning:

    ```jo
    // Scala example - hard to understand without checking imports
    val point = Point(3, 4)
    point.draw()  // Where does draw() come from? Must check imports!
    ```

    The same expression `point.draw()` may succeed or fail depending on what's imported in the current module. This makes code hard to understand, maintain, and refactor.

    Global variables in most languages also breaks local reasoning, complicating
    or even compromising the design of correct and secure systems.

    Local reasoning is fundamental to rigorous reasoning about correctness and security,
    as well as to improve readability and long-term maintainability of programs.

## 2. Type Safety

**Principle:** A program is either rejected by the type checker with helpful errors or accepted and does not crash at runtime.

!!!info "The success of type systems"

    Static type checking is widely recognized beneficial in preventing common programming
    mistakes, improving maintainability, as well as enabling faster programs.

    For security, type safety is particularly important, it enables reasoning
    about security and design secure systems based on types.

    In the age of AI agents, static type checking can provide helpful feedback
    to improve the efficiency of code generation.

## 3. Freedom with Control

**Principle:** Programmers should have powerful features and more freedom in
prototyping, but the language should provide control mechanism to prevent misuse.

**Impliciation**: Never introduce a powerful feature if it is prone to misuse and no checks are effective.

!!!info "Prototyping VS. Production"

    In prototyping, programmers usually want more freedom and fewer restrictions.
    In contrast, for production systems, programmers want more restrictions and safety checks.

    Example: Whether function result type should be explicitly declared?

    The only reasonable way to satisfy both needs is to have a **free mode** and a **safe mode**.
    The free mode is the default. In the safe mode, the compiler performs more safety checks.

    The language should enforce the following invariants:
    _A valid program in safe mode should also be valid in free mode and remain semantically equivalent_.

## 4. Explicitness over Implicitness

**Principle:** The compiler should not perform complex guessing. Users should make their intent clear when it's not obvious.

**Benefits**:

- Simple and predicatable type inference
- Long-term maintainability
- LLM friendliness

## 5. Naming Discipline

**Principle:** Naming and name resolution should follow strict, predictable rules.

!!!info "Why naming is important"

    Naming is the most fundamental mechanism of abstraction in both programming
    and natural languages. Communication, reasoning and understanding are all
    based on names.

    A simple, solid, and consistent naming mechanism will greatly faciliate
    development and maintainability.

**Benefits**:

- Simplicity
- Learnability
- Consistency

## 6. Semantic Lucidity

**Principle:** Language semantics must be intuitive, mathematically clear and platform-independent.

**Benefits**:

- Platform portability
- Safe optimization
- Readability and long-term maintainability

## 7. Alan Kay's Rule

**Principle:** "Simple things should be simple, complex things should be possible".

!!!info "Optimize for simple and common cases"

    Language design needs to optimize usability for simple and common use cases.
    For example, polymorphic code are not the common use case. Obscure language
    features and complex code synthesis for polymorphic code makes the code
    even more difficult to understand and complicates the language.
