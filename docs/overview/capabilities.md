# Capability-Based Security

Capability-based security is a security model where access to resources is controlled by possession of unforgeable tokens called capabilities.

## The Confinement Problem

Lampson ([1973](https://doi.org/10.1145/362375.362389)) identified a fundamental challenge: how to prevent a program from accessing unauthorized resources or leaking information through covert channels. The difficulty arises because:

- Programs need some access to be useful, but any access can potentially be misused
- Covert channels (timing, storage, legitimate outputs) can leak information indirectly  
- Runtime enforcement mechanisms are complex and difficult to verify completely
- Perfect confinement conflicts with program functionality

## Capability-based Systems

Capability-based systems partially address the confinement problem by controlling resource access.

**What capabilities can do:**

- Prevent unauthorized resource access through explicit capability provision
- Eliminate ambient authority that makes access difficult to track
- Enable static verification of resource access through program interfaces
- Provide fine-grained, composable access control

**What capabilities cannot do:**

- Prevent covert channels (timing, resource usage, cache behavior, power consumption)
- Prevent information leakage through legitimate program outputs

Capabilities improve security by making access explicit and verifiable, but the theoretical limits identified by Lampson remain.

## Core Principles

**Principle of least privilege** - Programs receive only the minimum capabilities needed to function.

**No ambient authority** - Programs cannot access resources without explicit capabilities.

## In Programming Languages

For ease of programming, traditional programming languages provide ambient authority through:

- Global variables and functions
- File system access
- Network access
- System calls

Jo embraces the convenience of ambient authorities and make them safe:

- No global variables
- All resource access through explicit or context parameters
- Type system enforcement of capability requirements

## Capability Confinement

```jo
// Function is confined to only the File capability provided
def readConfig(file: File): Config =
  parseConfig(file.readLine)      // Can only read this specific file
  // Cannot access network, other files, or system resources

// Main function must explicitly provide capabilities
def main =
  val configFile = open("config.txt")  // Grants access to one file
  val config = readConfig(configFile) allow none  // Confined to that file only
  // readConfig cannot access anything beyond the provided file
```

## Fine-grained Confinement

Capabilities can be subdivided arbitrarily. A broad file access capability can be refined into specific operations:

```jo
// Function confined to only reading lines, not full file access
param readLine: () => Option[String]  // Refined from broader file capability

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

## Polymorphic Capabilities

Capabilities are polymorphic types, enabling easy substitution for testing and modularity:

```jo
// Function depends on abstract output capability
param output: String => Unit

def report(status: String): Unit =
  output("Status: " + status)  // Uses whatever output is provided

// Production: use real stdout
def main =
  report("System ready") with output = (msg => println msg)

// Testing: capture output for verification
def test(): String =
  val captured = { var content: String = "" }
  val mockOutput = (s: String) => captured.content = captured.content + s + "\n"
  
  report("Test complete") with output = mockOutput
  captured.content  // Returns captured output for assertion
```

Polymorphic capabilities enable dependency injection without frameworks while maintaining compile-time safety.

## Security Benefits

**Confinement** - Code cannot access undeclared resources.

**Auditability** - Resource access is visible in function signatures.

**Composability** - Capabilities can be combined and delegated safely.

**Testability** - Capabilities are polymorphic, enabling easy substitution of mock implementations for testing.
