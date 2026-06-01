# Object Definitions

## Overview

Object definitions provide a convenient syntax for defining singleton values with
associated behavior. An object combines a value binding with a class definition,
ensuring exactly one instance exists and is accessible through a named identifier.

::: info When to Use Sections vs Objects
If you need a **stateless** collection of functions that will **not** be used in union
types or as interface implementations, prefer **section definitions** instead. Sections
provide a simpler mechanism for grouping related functions without creating singleton
instances.

Use objects when you need:

- A singleton that implements an interface (e.g., `object ConsoleLogger` with `view Logger`)
- A singleton used in union types
- Type identity for a stateless singleton

Use sections when you need:

- Stateless utility functions (e.g., `MathUtils`)
- Namespace organization
:::

## Syntax

```
object_def    = "object" ident {object_member} ["end"]
object_member = view_decl | method
```

Objects have no constructor, no fields, and no type parameters. Members are methods
and direct views only:

```jo
interface Logger
  def log(msg: String): Unit
  def error(msg: String): Unit
end

object ConsoleLogger
  def log(msg: String): Unit = println(msg)
  def error(msg: String): Unit = eprintln(msg)

  view Logger
end
```

## Semantics

### Desugaring

An object definition desugars into a singleton binding, a pattern, and a class:

```jo
object A
  // members...
end

// Desugars to:
def A: A = ...  // singleton instance (backend-determined initialization)
pattern A: A = case _
class A
  // members...
end
```

::: info Object initialization
The exact initialization strategy is determined by the backend. Programmers should
not observe any semantic difference regardless of which strategy is used.
:::

### Restrictions

**No type parameters** — objects cannot be generic. Type parameters would require
parameterizing the singleton instance, contradicting the singleton pattern. Use a
class instead if parameterized behavior is needed.

**No fields or delegate views** — objects have no constructor and no field declarations.
Since delegate views (`view I = ref`) require a stable field reference, they are
naturally unavailable in objects. Only direct views (`view I`) are supported.

::: info Rationale: No Global Variables
Jo does not support global variables. Since objects desugar to module-level definitions,
allowing fields would introduce global state — even immutable `val` fields, which may
reference mutable class instances or create initialization order problems between objects.

If you need state or configuration, use a class instance passed as a context parameter.
:::

**No custom constructor** — the initialization of a singleton should be trivial. Objects
cannot define a constructor method.

**No instantiation** — attempting to create an instance with `new` is a compile-time
error. The singleton is accessed directly through the object name:

```jo
object Logger
  def log(msg: String): Unit = println(msg)
end

Logger.log("hello")    // correct
new Logger()           // Error: cannot instantiate object type
```

**Top-level only** — object definitions must appear at the top level of a namespace,
like class and interface definitions. Local object definitions are not allowed.

## Views

Objects support direct views only. View conformance is checked the same way as for
classes: all abstract methods of the interface must be implemented, and concrete
interface methods are inherited and cannot be overridden.

```jo
interface Formatter
  def format(value: Int): String
end

object HexFormatter
  def format(value: Int): String = "0x" + intToHexString(value)
  view Formatter
end

object DecFormatter
  def format(value: Int): String = value.toString()
  view Formatter
end

def display(value: Int, fmt: Formatter): Unit =
  println(fmt.format(value))

display(255, HexFormatter)  // Prints: 0xFF
display(255, DecFormatter)  // Prints: 255
```

## Union Types

Objects can participate in union types, unlike sections:

```jo
object None
class Some[T](value: T)

type Option[T] = None | Some[T]
```

## See Also

- [Class Definitions](class-definitions.md) - Full class features including fields and delegate views
- [Interface Definitions](interface-definitions.md) - Interface method requirements
- [Section Definitions](section-definitions.md) - Stateless function grouping
