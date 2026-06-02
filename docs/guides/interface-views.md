# Classes and Views

## Overview

**Views** are Jo's unified mechanism for fulfilling behavioral contracts. A class uses
`view` to declare that it satisfies an interface — either by implementing the required
methods directly, or by delegating to a stable reference that already satisfies it.

## Motivation

The traditional dichotomy between **inheritance** and **composition** is somewhat
artificial. In practice, programmers care primarily about **fulfilling behavioral
contracts** — ensuring that objects provide the capabilities required by the interfaces
they use.

Yet traditional OOP languages create a significant **asymmetry**:

- **Inheritance** is easy: declare it, get automatic subtype relationships
- **Composition** is tedious: write forwarding methods for every delegated method

This asymmetry pushes programmers toward inheritance even when composition is the better
fit, contradicting the widely-accepted principle of **"prefer composition over inheritance"**.

Views eliminate this asymmetry. The single `view` keyword covers both strategies:

- **`view I`** — implement the contract yourself: the class provides the methods directly
- **`view I = ref`** — delegate the contract: a held object provides the methods

Both create `C <: I`. Both are equally concise. The choice is a design decision about
where the implementation lives, not a tradeoff in convenience or type-system power.

```jo
interface Loggable
  def log(msg: String): Unit
end

interface Serializable
  def serialize(): String
end

class User(id: Int, name: String, logger: Loggable)
  def serialize(): String = "User(" + id + ", " + name + ")"
  view Serializable    // direct: User implements Serializable itself

  view Loggable = logger  // delegate: Loggable is provided by logger
end
```

`User` is a subtype of both `Serializable` and `Loggable`.

## Two View Forms

### Direct View: `view I`

The class implements all abstract methods of `I` in its own body. The compiler verifies
this and establishes `C <: I`.

```jo
interface Logger
  def log(msg: String): Unit
end

class ConsoleLogger
  def log(msg: String): Unit = println(msg)
  view Logger
end

def useLogger(l: Logger): Unit = l.log("hello")

val console = new ConsoleLogger()
useLogger(console)   // OK: ConsoleLogger <: Logger
```

### Delegate View: `view I = ref`

The class holds a stable reference `ref` that already conforms to `I`. The compiler
generates a forwarding method for each **abstract** method of `I`, delegating to `ref`.
The class becomes a subtype of `I` through these forwarders.

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

class Service(logger: Logger)
  view Logger = logger
end

// Service <: Logger, so no boilerplate at call sites
def useLogger(l: Logger): Unit = l.log("hello")

val service = new Service(someLogger)
useLogger(service)   // OK: Service <: Logger
service.log("hello") // OK: forwarded to service.logger.log("hello")
```

The delegate `ref` must be a **stable reference**: an immutable field or a chain of
immutable field selections. It must also conform to `I` (nominally: `ref.tpe <: I`).

## Design Decisions

### No Duplicate Views

A class cannot declare two views of the same interface — it would create ambiguity in
which view provides the implementation. The compiler rejects duplicate view declarations.

```jo
class Service(l1: Logger, l2: Logger)
  view Logger = l1
  view Logger = l2  // Error: duplicate view for Logger
end
```

### View Consistency

All members visible through a class form a single unified namespace. Conflicts between
direct members and view-provided members are reported at class definition time.

A class method cannot share a name with a synthesized forwarder from a delegate view:

```jo
interface Logger
  def log(msg: String): Unit
end

class Service(logger: Logger)
  def log(msg: String): Unit = ...  // Error: conflicts with forwarder for Logger.log
  view Logger = logger
end
```

Two delegate views cannot both forward a method of the same name:

```jo
interface Logger
  def log(msg: String): Unit
end

interface Auditor
  def log(event: String): Unit
end

class Service(logger: Logger, auditor: Auditor)
  view Logger = logger
  view Auditor = auditor  // Error: 'log' conflicts between Logger and Auditor forwarders
end
```

## See Also

- [Class Definitions](../language/definitions/class-definitions.md) - View conformance rules,
  member consistency, and technical details
- [Interface Definitions](../language/definitions/interface-definitions.md) - Interface method
  requirements and concrete method semantics
