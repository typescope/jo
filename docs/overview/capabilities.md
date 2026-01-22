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

These are _ambient authorities_: capabilities available by default without explicit authorization.

It is difficult to reason about confinement ([Lampson 1973](https://doi.org/10.1145/362375.362389)) in the presence of ambient authorities: there are so many side channels and no principled way to control them. This compromises fundamental security principles:

- **Principle of least privilege**: only provide the minimum capabilities needed to programs.

- **Complete mediation**: all access to resources must be verified.

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
  bar allow none                  // (1)!
  baz allow IO.stdout             // (2)!
  qux with IO.stdout = s => pass  // (3)!
```

1. `allow` controls what capabilities are permitted - this fails because `bar` needs `stdout`
2. `allow IO.stdout` permits the `stdout` capability - this succeeds
3. `with` provides a capability value - here redirecting output to a no-op

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
param readLine: () => Option[String] // (1)!

def lineCount(): Int =
  def recur(acc: Int): Int =
    match readLine()
      case Some _ => recur(acc + 1)
      case None   => acc
  recur(0)

def main =
  val file = open("data.txt")
  val readLineFun = () => if file.hasMore() then Some(file.readLine()) else None

  lineCount() with readLine = readLineFun allow none // (2)!
```

1. A refined capability: only line reading, not full file access
2. `allow none` proves `lineCount` uses no capabilities beyond `readLine`

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

## For Secure AI

Jo's capability system can confine AI-generated code at compile time:

```jo
param myOrders: (lastDays: Int) => List[Order] // (1)!

// AI-generated code: can only read orders, nothing else
def aiAnalyze(): Summary =
  val orders = myOrders(30)
  summarize(orders)

def main =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = (days: Int) => db.ordersFor(userId, days) // (2)!

  aiAnalyze() with myOrders = restricted allow none // (3)!
```

1. The only capability available to AI code
2. Restricted to this user's orders only
3. Compiler proves: AI code cannot access network, filesystem, or other data

The AI code cannot access the network, filesystem, or other users' data - the compiler enforces this statically. After type checking, no runtime isolation or sandboxing is needed.

See [AI Security](../security/solution.md) and [Security Demos](../demos/index.md) for more examples.

## See Also

- [Pattern-Oriented Programming](patterns.md) - Jo's powerful pattern system
- [Context Parameters](../language/concepts/context-parameters.md) - How capabilities work as parameters
- [Get Started](get-started.md) - Install Jo and run your first program
