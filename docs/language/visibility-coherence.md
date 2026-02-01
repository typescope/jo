# Visibility Control and Coherence Check

## Overview

Jo allows programmers to restrict usage of symbols to specific scopes -- a way to enforce information hiding.

Jo performs _coherence check_ to prevent unintended errors in access control by ensuring private symbols don't leak into public APIs.

!!! warning
    Visibility control is a mechanism for information hiding, not security enforcement.

## Motivation

Visibility control is a well-known mechanism for information hiding in programming.
However, it alone can lead to unintended errors. Consider:

```jo
section Internal
  private type Secret = Int

  // Error: private type Secret leaks into public function
  def process(x: Secret): Int = x + 1
end
```

The function `process` is public, but uses private type `Secret` in its signature. This creates problems:

- Users outside `Internal` cannot call `process` (they can't construct `Secret`)
- Or worse, the implementation detail is accidentally exposed

**Coherence checking** prevents such errors by verifying that private symbols don't appear in more public contexts.

## Core Principles

### Visibility Scope

Each symbol has a **visible scope** determined by its access modifiers and location:

1. **Local symbols**: Visible scope is the enclosing function
2. **Public symbols**: Inherit visible scope from parent (default)
3. **Private symbols**: `private` limits scope to immediate container (class, section, or namespace)
4. **Qualified private**: `private[N]` limits scope to container `N` (where `N` is a section or namespace name)
5. **Namespaces**: Have global visible scope

Note: There is no class inheritance in Jo.

### Two Forms of Checking

**Usage Check** verifies that a symbol is accessible at its usage site.

A symbol `S` can be used at position `P` only if `P` is within `S`'s visible scope.

```jo
section A
  private def helper(): Int = 42
end

def test() = A.helper()  // Error: cannot access private member helper
```

**Coherence Check** verifies that symbols in a definition's signature are at least as visible as the definition itself.

A symbol `X` appearing in definition `Y`'s signature is coherent if `X`'s visible scope contains `Y`'s visible scope. In other words, private symbols cannot appear in more public definitions.

```jo
section A
  private type Internal = Int

  // Error: Internal's scope (section A) does not contain publicFun's scope (global)
  def publicFun(x: Internal): Int = x

  // OK: Internal's scope contains privateFun's scope
  private def privateFun(x: Internal): Int = x
end
```

## Examples

### Qualified Private Visibility

Use `private[ContainerName]` to limit visibility to a specific enclosing container:

```jo
section Outer
  private[Outer] def visibleInOuter(): Int = 100

  section Inner
    // OK: can access private[Outer] from within Outer
    def useOuter(): Int = visibleInOuter()

    private[Inner] def visibleInInner(): Int = 200
  end

  // OK: can access private[Outer] within Outer
  def test1(): Int = visibleInOuter()

  // Error: cannot access private[Inner] from outside Inner
  def test2(): Int = Inner.visibleInInner()
end

// Error: cannot access private[Outer] from outside Outer
def test3(): Int = Outer.visibleInOuter()
```

This allows fine-grained control over visibility across nested containers.

### Parameter Adapters

Function adapters must be coherent with the defining function:

```jo
section A
  private def secretConv(x: Int): String = "secret"

  def show(s: String with [secretConv]): Unit = println s     // Error
  private def show2(s: String with [secretConv]): Unit = println s  // OK
end
```

### Auto Parameters

Value candidates must be coherent with the defining function:

```jo
type Eq[T] = (T, T) => Bool

section A
  private def eqSecret: Eq[Int] = (a, b) => a == b

  def compare(x: Int, y: Int)(auto eq: Eq[Int] with [eqSecret]): Bool = eq(x, y)         // Error
  private def compare2(x: Int, y: Int)(auto eq: Eq[Int] with [eqSecret]): Bool = eq(x, y) // OK
end
```

Member candidates check the type in bracket notation:

```jo
section A
  private type Secret = Int

  def areEqual(x: Secret, y: Secret)(auto eq: Eq[Secret] with [[Secret].==]): Bool = ...  // Error
end
```

Types in auto parameters are checked like regular parameter types:

```jo
section A
  private type Internal = String
  def eqInternal: Eq[Internal] = (a, b) => a == b

  def process(x: Internal)(auto eq: Eq[Internal] with [eqInternal]): Bool = ...  // Error
end
```

### Context Parameters

Context parameter symbols in `receives` clause must be coherent:

```jo
section A
  private param secret: String

  def getSecret(): String receives secret = secret         // Error
  private def getSecret2(): String receives secret = secret  // OK
end
```

Types in context parameter declarations must be coherent:

```jo
section A
  private type Config = Int

  param cfg: Config          // Error
  private param cfg2: Config  // OK
end
```

## Verification Scope

Coherence verification applies to the following definition components:

| Definition Type | Checked Components |
|----------------|-------------------|
| **Function** | parameter types, parameter adapters, auto parameters, auto candidates, result type, context parameters (`receives` clause) |
| **Pattern** | parameter types, result type |
| **Type** | right-hand side types |
| **Class** | fields, methods |
| **Context Parameter** | parameter type |

Usage checks apply to all member selections and symbol references.
