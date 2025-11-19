# Visibility Coherence

## Overview

Visibility coherence ensures that private symbols do not leak into public APIs. The compiler performs static checks to verify that symbols appearing in top-level definitions have coherent visibility.

!!! warning

    Visibility is a mechanism to handle information hiding in programming, it should never be used as a facility to enforce security.

## Motivation

Prevent visibility violations where private implementation details become part of public interfaces:

```jo
section Internal
  private type Secret = Int

  // Error: private type Secret leaks into public function
  def process(x: Secret): Int = x + 1
end
```

Without visibility coherence checks, either users outside `Internal` cannot call `process` or the implementation details are exposed to end users.

## Core Principles

### Visibility Scope

Each symbol has a **visible scope** determined by its access modifiers and location:

1. The visible scope of a local symbol is its enclosing function.

2. A top-level symbol by default inherits visible scope of its parent.

3. If X is declared as `private[N]`, its visible scope is N. And it is an error if N is bigger than the visible scope of the owner of X.

4. Namespaces have global visible scope.

### Two Forms of Checking

**Usage Check**: A symbol must be visible at its usage site.

```jo
section A
  private def helper(): Int = 42
end

def test() = A.helper()  // Error: cannot access private member helper
```

**Coherence Check**: A symbol X appearing in definition Y is coherent if X's visible scope contains Y's visible scope.

In other words, **a private symbol cannot appear in a more public definition**.

```jo
section A
  private type Internal = Int

  // Error: Internal's visible scope (section A) does not contain
  // publicFun's visible scope (public), so coherence is violated
  def publicFun(x: Internal): Int = x

  // OK: Internal's visible scope contains privateFun's visible scope
  private def privateFun(x: Internal): Int = x
end
```

## Examples

### Parameter Adapters

**Function adapters** must be coherent with the defining function:

```jo
section A
  private def secretConv(x: Int): String = "secret"

  // Error: private adapter secretConv leaks in public function
  def show(s: String with [secretConv]): Unit = println s

  // OK: private adapter in private function
  private def show2(s: String with [secretConv]): Unit = println s
end
```

**Member adapters** check the type in bracket notation:

```jo
section A
  private class Hidden
    def toString: String = "hidden"
  end

  // Error: private type Hidden leaks via member adapter
  def display(s: String with [.toString]): Hidden = ...
end
```

### Auto Parameters

**Value candidates** must be coherent with the defining function:

```jo
type Eq[T] = (T, T) => Bool

section A
  private def eqSecret: Eq[Int] = (a, b) => a == b

  // Error: private candidate eqSecret leaks in public function
  def compare(x: Int, y: Int)(auto eq: Eq[Int] with [eqSecret]): Bool = eq(x, y)

  // OK: private candidate in private function
  private def compare2(x: Int, y: Int)(auto eq: Eq[Int] with [eqSecret]): Bool = eq(x, y)
end
```

**Member candidates** check the type in bracket notation:

```jo
section A
  private type Secret = Int

  // Error: private type Secret leaks via member candidate type
  def areEqual(x: Secret, y: Secret)(auto eq: Eq[Secret] with [[Secret].==]): Bool = eq(x, y)
end
```

**Types in auto parameters** are checked like regular parameter types:

```jo
section A
  private type Internal = String

  def eqInternal: Eq[Internal] = (a, b) => a == b

  // Error: private type Internal leaks in auto parameter type
  def process(x: Internal)(auto eq: Eq[Internal] with [eqInternal]): Bool = ...
end
```

### Context Parameters

**Context parameter symbols** in `receives` clause must be coherent:

```jo
section A
  private param secret: String

  // Error: private context parameter secret leaks in public function
  def getSecret(): String receives secret = secret

  // OK: private context parameter in private function
  private def getSecret2(): String receives secret = secret
end
```

**Types in context parameter declarations** must be coherent:

```jo
section A
  private type Config = Int

  param cfg: Config  // Error: private type in public context parameter
end

section B
  private type Config = Int

  private param cfg: Config  // OK: private context parameter with private type
end
```

## When Checks Are Performed

The usage of restricted members is checked during type checking for all
selections to make sure the member is visible in the current scope.

Visibility coherence checks run **after effect analysis** in the compilation pipeline. This is necessary because:

1. Effect analysis infers context parameters (`receives` clause)
2. Type inference may infer result types

Only after these phases complete can we check the full visibility coherence of inferred types and dependencies.

## Summary

Visibility coherence maintains API integrity by preventing private symbols from appearing in public interfaces.
This ensures public APIs remain usable and private implementation details stay hidden.
