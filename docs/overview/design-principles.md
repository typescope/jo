# Design Principles

This document outlines the core design principles that guide Jo's language design decisions.

## 1. Local Reasoning

**Principle:** The behavior of code should be understandable by examining the code locally, without needing to search through distant parts of the codebase.

!!!info "Why This Matters"

    Many languages with implicit features (Scala implicits, Haskell type classes, Swift extensions, Rust traits) require searching through imports and lexical scope to determine what methods are available on a value. This breaks local reasoning:

    ```Scala
    // Scala example - hard to understand without checking imports
    val point = Point(3, 4)
    point.draw()  // Where does draw() come from? Must check imports!
    ```

    The same expression `point.draw()` may succeed or fail depending on what's imported in the current module. This makes code hard to understand, maintain, and refactor.

    Global variables in most languages also breaks local reasoning.

    Local reasoning is fundamental to rigorous reasoning about correctness and security.

## 2. Freedom with Checks

**Principle:** Users should have powerful features, but the language should provide checks to prevent misuse.

**Impliciation**: Never introduce a powerful feature if it is prone to misuse.

## 3. Explicitness over Implicitness

**Principle:** The compiler should not perform complex guessing. Users should make their intent clear when it's not obvious.

**Benefits**:

- Simple and predicatable type inference
- Long-term maintainability
- LLM friendliness

## 4. Naming Discipline

**Principle:** Naming and name resolution should follow strict, predictable rules.

**Benefits**:

- Simplicity
- Learnability
- Consistency

## 5. Semantic Lucidity

**Principle:** Semantics must be clear and cross-platform.

**Benefits**:

- Platform portability
- Safe optimization
- Long-term maintainability

## 6. Alan Kay's Rule

**Principle:** "Simple things should be simple, complex things should be possible".

**Implication**: Language design needs to only focus usability for simple cases.
