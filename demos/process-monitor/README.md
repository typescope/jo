# System Monitor Agent

This example demonstrates how platforms can provide system capabilities through **context parameters**. It shows how user code can access process information, system details, and logging functionality through a type-safe capability interface without direct access to Node.js APIs.

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
│                     │  - interface Process
│  param process      │  - interface System
│  param system       │  - interface Logger
│  param logger       │  - param declarations
└──────────┬──────────┘
           │ provided by
           ▼
┌─────────────────────┐
│ PlatformRuntime.jo  │  Runtime world (trusted)
│                     │  - class ProcessImpl
│  platformMain       │  - class SystemImpl
│                     │  - class LoggerImpl
└──────────┬──────────┘  - Uses js intrinsic for Node.js
           │ uses
           ▼
┌─────────────────────┐
│  js.javascript      │  Base JS runtime
│  (js intrinsic)     │  - Node.js interop
└─────────────────────┘
```

## Files

### PlatformAPI.jo

Declares capability interfaces and context parameters:

```jo
interface Process
  def listProcesses(): String
  def countProcesses(): Int
  def findByName(processName: String): Int
  def getCurrentPID(): Int
  def getCurrentMemoryMB(): Int
end

interface System
  def uptime(): Int
  def platform(): String
  def arch(): String
  def hostname(): String
end

interface Logger
  def info(message: String): Unit
  def debug(message: String): Unit
end

param process: Process
param system: System
param logger: Logger
```

### PlatformRuntime.jo

Implementation classes with inlined operations:

```jo
class ProcessImpl
  def listProcesses(): String =
    val output = js "require('child_process').execSync('ps aux').toString()"
    output

  def countProcesses(): Int =
    val output = js "require('child_process').execSync('ps aux | wc -l').toString()"
    val countStr = js "output.trim()"
    val count = js "parseInt(countStr, 10)"
    count - 1  // Subtract header line

  def findByName(processName: String): Int =
    val command = "pgrep -n " + processName  // -n = newest
    var result = ""
    js "try { result = require('child_process').execSync(command).toString().trim()} catch (e) { result = '-1' }"
    val pid = js "parseInt(result, 10)"
    pid

  def getCurrentPID(): Int =
    val pid = js "process.pid"
    pid

  def getCurrentMemoryMB(): Int =
    val memBytes = js "process.memoryUsage().heapUsed"
    val memMB = js "Math.round(memBytes / (1024 * 1024))"
    memMB

  view SystemAPI.Process
end

interface OS
  def uptime(): Int
  def platform(): String
  def arch(): String
  def hostname(): String
end

def os: OS = js "require('os')"

class SystemImpl
  def uptime(): Int =
    val uptimeSeconds = os.uptime()
    val rounded = js "Math.round(uptimeSeconds)"
    rounded

  def platform(): String = os.platform()

  def arch(): String = os.arch()

  def hostname(): String = os.hostname()

  view SystemAPI.System
end

class LoggerImpl(console: String => Unit)
  def info(message: String): Unit =
    begin
      print "[INFO] "
      println message
    end with stdout = console

  def debug(message: String): Unit =
    begin
      print "[DEBUG] "
      println message
    end with stdout = console

  view SystemAPI.Logger
end

def platformMain: Unit receives stdout =
  val processImpl = new ProcessImpl()
  val systemImpl = new SystemImpl()
  val loggerImpl = new LoggerImpl(stdout)

  startMonitor with
    process = processImpl,
    system = systemImpl,
    logger = loggerImpl
```

**Key technique**: Implementation classes directly implement the interface methods. For cleaner abstraction, the Node.js `os` module is wrapped via an `OS` interface and bound to `def os: OS = js "require('os')"`, allowing `SystemImpl` to call `os.uptime()`, `os.platform()`, etc. instead of inline `js` calls. Each class declares a view to the corresponding interface (`view SystemAPI.Process`, etc.). Class instances are created and passed via context parameters.

### UserApp.jo
Receives context parameters:

```jo
def analyzeSystem(): Unit receives stdout, process, logger =
  val totalProcs = process.countProcesses()
  println ("Total running processes: " + totalProcs)

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
  -link jo.main=SystemRuntime.platformMain \
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
