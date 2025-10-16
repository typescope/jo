# System Monitor: Extending Jo Runtime with Real Node.js Capabilities

This example demonstrates **extending the Jo runtime with real Node.js capabilities** using the `-runtime` flag and the `js` intrinsic function. It shows how platforms can expose new system APIs to user code in a controlled, capability-based manner.

## What This Demonstrates

Unlike mock examples, this uses **real Node.js APIs**:
- `child_process.execSync()` - Execute shell commands (ps, pgrep)
- `os.uptime()`, `os.platform()`, `os.arch()`, `os.hostname()` - System information
- `process.pid`, `process.memoryUsage()` - Current process info

User code analyzes live system processes without direct access to Node.js.

## Architecture

```
┌─────────────────────┐
│   UserApp.stk       │  User code (untrusted)
│ (Process Analyzer)  │  - Analyzes process list
└──────────┬──────────┘  - Searches for specific processes
           │ imports     - Only uses SystemAPI capabilities
           ▼
┌─────────────────────┐
│  PlatformAPI.stk    │  Pure world (interface)
│ (System Monitor API)│  - Process.listProcesses()
└──────────┬──────────┘  - System.platform(), uptime()
           │ implemented by
           ▼
┌─────────────────────┐
│ PlatformRuntime.stk │  Runtime world (trusted)
│  (Node.js interop)  │  - Uses js intrinsic
└──────────┬──────────┘  - Calls Node.js child_process, os
           │ uses
           ▼
┌─────────────────────┐
│  stk.runtime.JS     │  Base runtime
│  (js intrinsic)     │  - Provides js "..." for interop
└─────────────────────┘  - I/O via stdout
```

## Files

### PlatformAPI.stk
Declares system monitoring capabilities:

```stk
section Process
  defer def listProcesses(): String receives stdout
  defer def countProcesses(): Int receives stdout
  defer def findByName(processName: String): Int receives stdout
  defer def getCurrentPID(): Int receives stdout
  defer def getCurrentMemoryMB(): Int receives stdout
end

section System
  defer def uptime(): Int receives stdout
  defer def platform(): String receives stdout
  defer def arch(): String receives stdout
  defer def hostname(): String receives stdout
end
```

### PlatformRuntime.stk
**Real Node.js implementation** using `js` intrinsic:

```stk
import stk.runtime.JS.js

def listProcesses(): String receives stdout =
  val output = js "require('child_process').execSync('ps aux').toString()"
  output

def countProcesses(): Int receives stdout =
  val output = js "require('child_process').execSync('ps aux | wc -l').toString()"
  val countStr = js "output.trim()"
  val count = js "parseInt(countStr, 10)"
  count - 1  // Subtract header line

def platform(): String receives stdout =
  val plat = js "require('os').platform()"
  plat

def getCurrentPID(): Int receives stdout =
  val pid = js "process.pid"
  pid
```

**Key technique**: Import `stk.runtime.JS.js` to use the `js` intrinsic, which executes JavaScript code and interoperates with Node.js.

### UserApp.stk
Process analyzer using only SystemAPI:

```stk
def analyzeSystem(): Unit receives stdout =
  val totalProcs = Process.countProcesses()
  ("Total running processes: " + totalProcs.intToStr).println

  val procList = Process.listProcesses()
  // ... analyze process list

  val nodePid = Process.findByName("node")
  if nodePid >= 0 then
    ("Found node process: PID " + nodePid.intToStr).println
```

User code **cannot**:
- Call `js` directly (not imported)
- Access `require()`, `process`, `os` modules
- Execute arbitrary shell commands

User code **can only**:
- Use capabilities declared in SystemAPI
- Perform pure computation
- Compose results from platform capabilities

## Three-Stage Compilation

### Stage 1: Compile Platform API
```bash
bin/jo build-lib PlatformAPI.stk -d out/api
```

Creates pure interface declarations.

### Stage 2: Compile Platform Runtime (The Key Step)
```bash
bin/jo build-lib PlatformRuntime.stk \
  -lib libs/runtime/js:out/api \
  -d out/runtime
```

**Critical**: Links to `libs/runtime/js` to access the `js` intrinsic function, enabling real Node.js interop.

### Stage 3: Compile User Application
```bash
bin/jo build -js \
  -no-detect-main \
  -link stk.Main.main=SystemAPI.Monitor.startMonitor \
  -link SystemAPI.Process.listProcesses=SystemRuntime.ProcessImpl.listProcesses \
  -link SystemAPI.Process.countProcesses=SystemRuntime.ProcessImpl.countProcesses \
  ... (all capability mappings) \
  -lib out/api \
  -runtime out/runtime \
  UserApp.stk \
  -o out/monitor.js
```

**The `-runtime` flag**: Provides the custom extended runtime to user code.

## Running

```bash
cd tests/custom/web-framework
./build.sh
```

**Real output** from your system:
```
[INFO] System Monitor Starting...
[INFO] =========================
Platform: linux
Architecture: x64
Hostname: your-hostname
Uptime: 123456 seconds

Current Process PID: 12345
Current Process Memory: 8 MB

[INFO] Running user analysis...
=== Process Analysis ===

Total running processes: 287

Top 10 processes:
-----------------
USER         PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
root           1  0.0  0.1 167896 11234 ?        Ss   Jan01   0:23 /sbin/init
root           2  0.0  0.0      0     0 ?        S    Jan01   0:00 [kthreadd]
...

Searching for common processes...
✓ Found node process: PID 12345
✓ Found bash process: PID 23456
✓ Found systemd process: PID 1

Process List Analysis:
---------------------
✓ Processes running as root detected
✓ Node.js processes detected

[DEBUG] Analysis complete
[INFO] Monitor shutting down
```

## How the `js` Intrinsic Works

From `runtime/JS.stk`:
```stk
def js(s: String): Bottom = abort("primitive js")
```

The compiler recognizes `js` as a **primitive** and:
1. Injects the JavaScript code directly into generated output
2. Allows referencing Jo local variables from the JS string
3. Returns values from JS back to Jo code

Example:
```stk
val n = 42
val doubled = js "n * 2"  // JS code can reference Jo variable 'n'
```

Generated JavaScript:
```javascript
const n = 42;
const doubled = n * 2;
```

## Security Properties

1. **User code cannot perform arbitrary system calls**
   - No access to `js` intrinsic (not imported)
   - Cannot `require()` Node.js modules directly
   - Cannot execute shell commands

2. **Platform controls capabilities**
   - All system access via SystemAPI declarations
   - Platform implements using `js` intrinsic
   - Type system enforces capability usage

3. **Custom entry point**
   - Framework controls execution flow
   - User implements callback (`analyzeSystem`)

4. **Auditable security**
   - All capabilities visible in PlatformAPI
   - All implementations visible in PlatformRuntime
   - User code scope is strictly limited

## Extending to Other Capabilities

This pattern works for any Node.js API:

**File system operations:**
```stk
def readFileContent(path: String): String receives stdout =
  val content = js "require('fs').readFileSync(path, 'utf8')"
  content
```

**HTTP requests:**
```stk
def httpGet(url: String): String receives stdout =
  val response = js "require('https').get(url).toString()"
  response
```

**Database queries:**
```stk
def dbQuery(sql: String): String receives stdout =
  val result = js "db.query(sql)"  // assumes db is available
  js "JSON.stringify(result)"
```

**Network sockets:**
```stk
def createTcpServer(port: Int): Unit receives stdout =
  js "require('net').createServer().listen(port)"
```

## Use Cases

This capability extension pattern enables:

1. **Multi-tenant platforms**: Each tenant gets isolated capability set
2. **LLM code execution**: Safe execution of AI-generated code
3. **Plugin systems**: Third-party code with controlled access
4. **Serverless functions**: User functions with platform-provided APIs
5. **Testing frameworks**: Mock vs. real implementations via different runtimes
6. **Educational platforms**: Students write code with restricted capabilities

## Key Takeaway

The `-runtime` flag combined with the `js` intrinsic enables platforms to:
- **Expose any Node.js capability** in a type-safe, controlled manner
- **Extend the base runtime** with custom APIs
- **Enforce capability discipline** at compile time
- **Achieve zero runtime overhead** (direct JS calls, no indirection)

User code operates in a **capability sandbox** - it can only do what the platform explicitly allows through the API surface.
