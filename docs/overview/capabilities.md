# Capability-Oriented Programming

Jo supports capability-oriented programming for designing secure systems.
Capability-based security is a security model where access to resources and services is controlled by capabilities.

More concretely, Jo follows the [object-capability model][1] where the
capabilities are service and resource references.  Reasoning about security
becomes easy if the only way to get more capabilities is through public
interface of existing references: we can control what a submodule can do by
controlling the references that it may receive (if all side channels are
removed). This is called "_only connectivity begets connectivity_" in
object-capability models.

[1]: https://en.wikipedia.org/wiki/Object-capability_model

## Security Principles

For ease of programming, traditional programming languages provide too many capabilities:

- Global variables
- File system access
- Network access
- System calls
- Foreign Function Interface (FFI)
- Reflective language features
- Control flow effects

Such design makes usage of the underlying services easy, but compromises the
fundamental security principles:

- **Principle of least privilege**: only provide the minimum capabilities needed to programs.

- **No ambient authority**: Programs cannot access resources if not explicit authorized.

It is difficult to reason about confinement ([Lampson 1973](https://doi.org/10.1145/362375.362389)) in such situations: there are
so many side channels and there are no principled and reliable way to control
them.

Jo embraces the convenience of ambient authorities and make them safe:

- No global variables
- All resource access through explicit parameters
- Type system enforcement of capability specification

## Capability Provision and Control

The capabilities can be provied to a nested scope via the keyword `with` and
controlled via `allow`, as the following example demonstrates:

```jo
def foo() = println "foo"                     // inferred capability: stdout
def bar() = foo                               // inferred capability: stdout

def baz() = println "baz"                     // inferred capability: stdout

def qux() receives IO.stdout = println "qux"  // explicit capability: only stdout

def main =
  bar allow none                  // error
  baz allow IO.stdout             // OK
  qux with IO.stdout = s => pass  // ignore output
```

Gives the following errors:

```
---------- Error at tests/warn/param-allow.jo:10:3 ---------------
|   bar allow none                  // error
|   ^^^
|   Parameter not allowed: stdout

The following is the trace that leads to the problem:
├──   bar allow none                  // error	[ tests/warn/param-allow.jo:10:3 ]
│     ^^^
├── def bar() = foo	[ tests/warn/param-allow.jo:2:13 ]
│               ^^^
└── def foo() = println "foo"	[ tests/warn/param-allow.jo:1:13 ]
                ^^^^^^^
```

The compiler statically verifies that

1. all required capabilities (direct and indirect) must be provided;
2. the specification in `allow` and explicit `receives` is followed.

## Fine-grained Confinement

Capabilities can be subdivided arbitrarily. A broad file access capability can be refined into specific operations:

```jo
param readLine: () => Option[String]  // Refined from broader file capability

// Function confined to only reading lines, not full file access
def lineCount(): Int =
  def recur(acc: Int): Int =
    match readLine()
      case Some _ => recur(acc + 1)  // Can only read lines
      case None   => acc             // Cannot write, seek, or delete
  recur(0)

// Caller provides refined capability, not full file access
def main =
  val file = open("data.txt")
  val readLineFun = () => if file.hasMore() then Some(file.readLine()) else None

  // lineCount gets only line-reading capability, nothing more
  lineCount() with readLine = readLineFun allow none
```

There is no limit to how we can subdivide a capability. This is a major difference between capability-based systems and effect systems: capabilities can be both composed and refined, while effects can be only combined for the sake of purity.

## Parametric Capabilities

Capabilities are parameters, enabling easy substitution for testing and modularity:

```jo
def report(status: String): Unit =
  println("Status: " + status)  // Uses stdout

// Production: use real stdout
def main =
  report("System ready")

// Testing: capture output for verification
class OutputCapture
  var content: String = ""

def test(): String =
  val captured = new OutputCapture
  val buffer = (s: String) => captured.content = captured.content + s + "\n"

  report("Test complete") with stdout = buffer
  captured.content  // Returns captured output for assertion
```

Parametric capabilities enable dependency injection without frameworks while maintaining compile-time safety.
