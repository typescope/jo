# Capability-Based Security

Capability-based security is a security model where access to resources is controlled by possession of unforgeable tokens called capabilities.

## Core Principles

**Principle of least privilege** - Programs receive only the minimum capabilities needed to function.

**No ambient authority** - Programs cannot access resources without explicit capabilities.

## In Programming Languages

For ease of programming, traditional programming languages provide ambient authority through:

- Global variables
- File system access
- Network access
- System calls

Jo embraces the convenience of ambient authorities and make them safe:

- No global variables
- All resource access through explicit context parameters
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

## Parametric Capabilities

Capabilities are parameters, enabling easy substitution for testing and modularity:

```jo
// Function depends on abstract output capability
param output: String => Unit

def report(status: String): Unit =
  output("Status: " + status)  // Uses whatever output is provided

// Production: use real stdout
def main =
  report("System ready") with output = (msg => println msg)

// Testing: capture output for verification
class OutputCapture
  var content: String = ""
end

def test(): String =
  val captured = new OutputCapture
  val mockOutput = (s: String) => captured.content = captured.content + s + "\n"

  report("Test complete") with output = mockOutput
  captured.content  // Returns captured output for assertion
```

Parametric capabilities enable dependency injection without frameworks while maintaining compile-time safety.
