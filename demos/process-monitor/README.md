# System Monitor Demo

This demo demonstrates how platforms can provide system capabilities through **context parameters**. It shows how user code can access process information, system details, and logging functionality through a type-safe capability interface without direct access to Node.js APIs.

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
│  jo.runtime.JS      │  Base JS runtime
│  (js intrinsic)     │  - Node.js interop
└─────────────────────┘
```

## Files

### PlatformAPI.jo

Declares capability types and context parameters:

```jo
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

Implementation functions are organized in three sections (`Process`, `System`, `Logger`), then provided via `with` clause:

```jo
section Process
  def listProcessesImpl(): String =
    val output = js "require('child_process').execSync('ps aux').toString()"
    output

  def countProcessesImpl(): Int =
    // ... Node.js implementation
end

section System
  def uptimeImpl(): Int =
    val uptimeSeconds = js "require('os').uptime()"
    // ...
end

section Logger
  def infoImpl(message: String): Unit receives stdout =
    print "[INFO] "
    println message
end

def platformMain: Unit receives stdout =
  startMonitor with
    process = {
      def listProcesses(): String = Process.listProcessesImpl()
      def countProcesses(): Int = Process.countProcessesImpl()
      // ...
    },
    system = {
      def uptime(): Int = System.uptimeImpl()
      // ...
    },
    logger = {
      def info(message: String): Unit = Logger.infoImpl(message)
      // ...
    }
```

**Key technique**: Implementation functions organized in sections, then capabilities provided in a single `with` expression.

### UserApp.jo
Receives context parameters:

```jo
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

## Running

```bash
demos/process-monitor/build.sh
```

## Security Properties

Context parameters provide strong security guarantees:

1. **User code cannot access undeclared capabilities**

   - Can only access declared `param` objects

2. **Platform controls implementations**

   - Via `with` clause in runtime

3. **Type-safe capability access**

   - Enforced at compile time

4. **No runtime surprises**

   - Resolves at compile/link time
   - Zero runtime overhead

## Key Takeaway

- Context parameters provide an **object-oriented** and **concise** approach to capability provision.
- Capabilities are naturally grouped into typed objects (Process, System, Logger), reducing linking verbosity while maintaining strong security guarantees.
- The platform controls all implementations, and user code can only access capabilities explicitly declared in the API.
