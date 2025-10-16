# System Monitor: Context Parameters Approach

This is an alternative implementation of the system monitor demo using **context parameters** instead of deferred functions. It demonstrates how platforms can provide capabilities through ambient context.

## Key Differences from Deferred Functions Approach

### Deferred Functions (`process-monitor`)
- Capabilities declared as `defer def` functions
- Each capability mapped individually via `-link` flags
- Platform implements each deferred function separately
- More explicit, more `-link` flags needed

### Context Parameters (`process-monitor-ctx`)
- Capabilities declared as **object types** with methods
- Context parameters declared with `param name: Type`
- Platform provides implementations via `with` clause
- Single entry point link, capabilities flow through context
- More concise linking

## Architecture

```
┌─────────────────────┐
│   UserApp.jo        │  User code (untrusted)
│ (Process Analyzer)  │  - receives process, logger
└──────────┬──────────┘  - Uses context params only
           │ receives
           ▼
┌─────────────────────┐
│  PlatformAPI.jo     │  Pure world (interface)
│                     │  - type Process = { ... }
│  param process      │  - type System = { ... }
│  param system       │  - param declarations
│  param logger       │
└──────────┬──────────┘
           │ provided by
           ▼
┌─────────────────────┐
│ PlatformRuntime.jo  │  Runtime world (trusted)
│  platformMain       │  - startMonitor with process = { ... }
└──────────┬──────────┘  - Uses js intrinsic for Node.js
           │ uses
           ▼
┌─────────────────────┐
│  jo.runtime.JS      │  Base runtime
│  (js intrinsic)     │  - Node.js interop
└─────────────────────┘
```

## Files

### PlatformAPI.jo

Declares capability types and context parameters:

```stk
type Process = {
  def listProcesses(): String
  def countProcesses(): Int
  def findByName(processName: String): Int
  def getCurrentPID(): Int
  def getCurrentMemoryMB(): Int
}

type System = {
  def uptime(): Int
  def platform(): String
  def arch(): String
  def hostname(): String
}

type Logger = {
  def info(message: String): Unit
  def debug(message: String): Unit
}

param process: Process
param system: System
param logger: Logger
```

### PlatformRuntime.jo
Provides context via `with` clause:

```stk
def platformMain: Unit receives stdout =
  startMonitor with
    process = {
      def listProcesses(): String =
        val output = js "require('child_process').execSync('ps aux').toString()"
        output

      def countProcesses(): Int =
        // ... Node.js implementation
    },
    system = {
      def uptime(): Int =
        val uptimeSeconds = js "require('os').uptime()"
        // ...
    },
    logger = {
      def info(message: String): Unit =
        print "[INFO] "
        println message
    }
```

**Key technique**: All capabilities provided in a single `with` expression.

### UserApp.jo
Receives context parameters:

```stk
def analyzeSystem(): Unit receives stdout, process, logger =
  val totalProcs = process.countProcesses()
  println ("Total running processes: " + (intToStr totalProcs))

  val procList = process.listProcesses()
  // ... analyze

  logger.debug("Analysis complete")
```

User code **cannot**:
- Create new context parameters
- Access capabilities not in the `receives` clause
- Call `js` or Node.js directly

## Compilation

### Stage 1: Compile Platform API
```bash
bin/jo build-lib PlatformAPI.jo -d out/api
```

### Stage 2: Compile Platform Runtime
```bash
bin/jo build-lib PlatformRuntime.jo \
  -lib libs/runtime-js:out/api \
  -d out/runtime
```

### Stage 3: Compile User Application
```bash
bin/jo build -js \
  -no-detect-main \
  -link jo.Main.main=SystemRuntime.platformMain \
  -link SystemAPI.Monitor.analyzeSystem=ProcessAnalyzer.Analysis.analyzeSystem \
  -lib out/api \
  -runtime out/runtime \
  UserApp.jo \
  -o out/monitor.js
```

**Notice**: Only **2 link flags** needed (vs. 15+ in deferred functions approach)!

## Running

```bash
cd demos/process-monitor-ctx
./build.sh
```

Output is identical to the deferred functions version.

## Comparison: Deferred Functions vs Context Parameters

| Aspect | Deferred Functions | Context Parameters |
|--------|-------------------|-------------------|
| **Capability Declaration** | `defer def func(): Type` | `type T = { ... }; param p: T` |
| **Implementation** | Individual function implementations | Object with all methods |
| **Linking** | One `-link` per capability | One `-link` for entry point |
| **Provision** | Compiler links at build time | Runtime provides via `with` |
| **User Code** | Calls deferred functions directly | Accesses via context parameter |
| **Verbosity** | More `-link` flags | Fewer `-link` flags |
| **Grouping** | Functions grouped logically | Naturally grouped in objects |
| **Type Safety** | Function signatures | Object types |

## When to Use Each Approach

### Use Deferred Functions When:
- You want fine-grained control over each capability
- Capabilities are independent functions
- Platform may provide different implementations per function
- Explicit linking is preferred for auditing

### Use Context Parameters When:
- Capabilities are naturally grouped (Process, System, Logger)
- You want less verbose linking
- Ambient capabilities make sense (logging, configuration)
- Object-oriented API design fits better

## Security Properties

Both approaches provide identical security:

1. **User code cannot access undeclared capabilities**
   - Deferred: Can only call declared `defer def` functions
   - Context: Can only access declared `param` objects

2. **Platform controls implementations**
   - Deferred: Via `-link` mappings
   - Context: Via `with` clause in runtime

3. **Type-safe capability access**
   - Both enforce type signatures at compile time

4. **No runtime surprises**
   - Both resolve at compile/link time
   - Zero runtime overhead

## Advanced: Mixing Both Approaches

You can combine deferred functions and context parameters:

```stk
// Some capabilities via deferred functions
defer def criticalOp(): Int

// Other capabilities via context
param logger: Logger

def userFunc(): Int receives logger =
  logger.info("Calling critical operation")
  criticalOp()  // Deferred function
```

This gives maximum flexibility for platform designers.

## Key Takeaway

Context parameters provide a more **object-oriented** and **concise** approach to capability provision, reducing linking verbosity while maintaining the same security guarantees as deferred functions. Choose based on your API design preferences and whether capabilities naturally group into objects.
