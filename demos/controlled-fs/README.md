# Controlled File System Demo

This demo demonstrates how to design a **sandboxed file system interface** using Jo's context parameters and interface types. User code can read, write, list, and delete files through a type-safe capability interface, while the trusted runtime ensures all operations are confined within a designated root directory.

## Architecture

```
┌─────────────────────┐
│   UserApp.jo        │  User code (untrusted)
│ (File Explorer)     │  - receives fs, logger
└──────────┬──────────┘  - Uses context params only
           │ receives
           ▼
┌─────────────────────┐
│  FileSystemAPI.jo   │  Pure world (interface)
│                     │  - interface FileSystem
│  param fs           │  - interface Logger
│  param logger       │  - union ReadResult
└──────────┬──────────┘  - class FileEntry
           │ provided by
           ▼
┌─────────────────────┐
│FileSystemRuntime.jo │  Runtime world (trusted)
│                     │  - class SandboxedFS
│  platformMain       │  - Path traversal prevention
│                     │  - class LoggerImpl
└──────────┬──────────┘  - Uses js intrinsic for Node.js
           │ uses
           ▼
┌─────────────────────┐
│  js.javascript      │  Base JS runtime
│  (js intrinsic)     │  - Node.js fs/path modules
└─────────────────────┘
```

## Files

### FileSystemAPI.jo

Declares data types, capability interfaces, and context parameters:

```jo
// Directory listing entry
class FileEntry(name: String, isDir: Bool, size: Int)

// Typed result for file reads - union distinguishes success from failure causes
union ReadResult = FileContent(content: String) | NotFound | AccessDenied(message: String)

interface FileSystem
  def readFile(path: String): ReadResult
  def writeFile(path: String, content: String): Bool
  def listDir(path: String): List[FileEntry]
  def deleteFile(path: String): Bool
  def exists(path: String): Bool
  def mkdir(path: String): Bool
end

interface Logger
  def info(message: String): Unit
  def warn(message: String): Unit
end

param fs: FileSystem
param logger: Logger
```

The `ReadResult` union type provides typed error handling: user code can pattern match to distinguish between successful reads, missing files, and access violations.

### FileSystemRuntime.jo

Trusted implementation with sandbox enforcement:

```jo
class SandboxedFS(root: RootEntry)
  def readFile(path: String): ReadResult =
    Impl.readFileImpl(path) with Impl.root = root

  // ... other methods delegate similarly ...

  view FileSystemAPI.FileSystem
end
```

### UserApp.jo

Receives context parameters and demonstrates file operations:

```jo
def exploreFiles: Unit receives stdout, fs, logger =
  // List directory contents
  val entries = fs.listDir(".")
  for entry in entries do
    if entry.isDir then println ("  [DIR]  " + entry.name)
    else println ("  [FILE] " + entry.name + " (" + entry.size + " bytes)")

  // Read with pattern matching on union type
  match fs.readFile("welcome.txt")
    case FileContent(content) => println content
    case NotFound => println "File not found!"
    case AccessDenied(msg) => println ("Access denied: " + msg)

  // Path traversal is blocked
  match fs.readFile("../../etc/passwd")
    case AccessDenied(msg) => println ("Blocked: " + msg)
    // ...
```

User code **cannot**:

- Access files outside the sandbox root
- Use path traversal (`../`) to escape the sandbox
- Access the sandbox root path directly
- Call `js` or Node.js directly

## Compilation

### Stage 1: Compile File System API

```bash
bin/jo build-lib FileSystemAPI.jo -d out/api
```

### Stage 2: Compile File System Runtime

```bash
bin/jo build-lib FileSystemRuntime.jo \
  -lib libs/runtime-js:out/api \
  -d out/runtime
```

### Stage 3: Compile User Application

```bash
bin/jo build -js \
  -link jo.main=FileSystemRuntime.platformMain \
  -link FileSystemAPI.exploreFiles=FileExplorer.exploreFiles \
  -lib out/api \
  -runtime out/runtime \
  UserApp.jo \
  -o out/app.js
```

## Running

```bash
demos/controlled-fs/build.sh
```

## Security Properties

1. **Sandbox enforcement** — All paths are resolved to absolute paths and verified to be under the sandbox root before any I/O operation

2. **Path traversal prevention** — `../` sequences are handled by `path.normalize`, and the resolved path is checked against the root boundary

3. **User code isolation** — User code has no access to `js` intrinsic, `process.argv`, or raw Node.js modules

4. **Typed error handling** — The `ReadResult` union type lets user code distinguish between "file not found" and "access denied" without exposing implementation details

5. **Runtime controls the root** — The sandbox root comes from `process.argv`, which is only accessible to the trusted runtime

## Key Takeaway

- Context parameters provide a natural way to implement **capability-based file access** where the runtime controls the security boundary.
- Union types (`ReadResult`) give user code **typed error information** without leaking implementation details — the user knows *why* a read failed, but cannot bypass the restriction.
- The sandboxing is enforced at the runtime level and cannot be circumvented by user code, regardless of what paths it constructs.
