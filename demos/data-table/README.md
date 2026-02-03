# Data Table Access Control with SQLite

This demo demonstrates **row-level security** in Jo, where different users can only access their own database rows. The demo shows how Jo's capability system enforces fine-grained access control at the application level.

## Key Concept

The `userId` and `dbPath` are passed as **command-line arguments** (NOT context parameters) and are only accessible to the trusted runtime. The runtime:

1. Reads `userId` from `process.argv[2]` and `dbPath` from `process.argv[3]`
2. Captures `userId` in closures when providing the `db` context parameter
3. Automatically injects `WHERE owner_id = ?` into all SQL queries

User code **never sees the userId or dbPath** and cannot bypass the filtering.

## Architecture

```
┌──────────────────┐
│                  │  UNTRUSTED: Queries documents
│    UserApp.jo    │  - NO access to userId
│                  │  - NO direct SQL access
│                  │  - receives db only
└────────┬─────────┘
         │ receives
         ▼
┌──────────────────┐
│ DatabaseAPI.jo   │  Interface: class + interface + param
│                  │  - class Document(...)
│ param db: DB     │  - interface DB
└────────┬─────────┘
         │ provided by
         ▼
┌──────────────────┐
│ Runtime.jo       │  TRUSTED: Enforces security
│                  │  - Reads userId from argv
│ def platformMain │  - class SecureDB(userId, dbHandle)
│   userId = argv  │  - view DatabaseAPI.DB
│   userApp with   │  - Auto-filters queries
│   db = secureDb  │  - User CANNOT access userId
└──────────────────┘
```

## Security Properties

1. **userId is inaccessible** - User code never sees `process.argv` or `userId`
2. **Captured in closures** - Runtime captures `userId` when providing `db`
3. **Automatic filtering** - All queries filtered by `WHERE owner_id = ?`
4. **Type-safe** - User code has only typed `db` interface, no raw SQL
5. **Compiler-enforced** - Even malicious user code cannot bypass security

## Files

### DatabaseAPI.jo

Defines class, interface, and context parameter:

```jo
class Document(id: Int, title: String, content: String, ownerId: Int, createdAt: String)

interface DB
  def queryMyDocuments(): List[Document]
  def getMyDocument(id: Int): Option[Document]
  def countMyDocuments(): Int
end

param db: DB
```

### Runtime.jo (Trusted)

Implementation class with interface view:

```jo
section Impl
  param dbHandle: Any

  def execQuery(sql: String, params: Any): String receives dbHandle =
    val db = dbHandle
    val stmt = js "db.prepare(sql)"
    val rows = js "stmt.all(...params)"
    rows

  def queryMyDocumentsImpl(userId: Int): List[Document] receives dbHandle =
    val sql = "SELECT * FROM documents WHERE owner_id = ?"
    val rows = execQuery(sql, js "[userId]")
    // ... build document list
end

class SecureDB(userId: Int, dbHandle: Any)
  def queryMyDocuments(): List[Document] =
    Impl.queryMyDocumentsImpl(userId) with Impl.dbHandle = dbHandle

  def getMyDocument(id: Int): Option[Document] =
    Impl.getMyDocumentImpl(userId, id) with Impl.dbHandle = dbHandle

  def countMyDocuments(): Int =
    Impl.countMyDocumentsImpl(userId) with Impl.dbHandle = dbHandle

  view DatabaseAPI.DB
end

def platformMain: Unit receives stdout =
  // Read userId from command line - ABORT if missing
  val userIdStr = js "process.argv[2]"
  if userIdStr == js "undefined" then
    abort "Missing userId argument"

  val userId = js "parseInt(process.argv[2])"

  // Read database path from command line - ABORT if missing
  val dbPath = js "process.argv[3]"
  if dbPath == js "undefined" then
    abort "Missing database path argument"

  // Open database
  val dbHandle = js "new DatabaseSync(dbPath)"

  // Create secure database instance with captured userId
  val secureDb = new SecureDB(userId, dbHandle)

  // Provide db implementation
  DatabaseAPI.analyzeDocuments with DatabaseAPI.db = secureDb
```

**Key technique**: `class SecureDB` captures `userId` and `dbHandle` as constructor parameters and implements the `DB` interface via `view DatabaseAPI.DB`. User code receives the interface but cannot access the captured userId.

### UserApp.jo (Untrusted)

Uses database through typed interface:

```jo
def analyzeDocuments: Unit receives stdout, db =
  // User code has NO ACCESS to userId!
  // Can only call db methods which are already scoped

  val docs = db.queryMyDocuments()  // Automatically filtered!
  for doc in docs do
    println (doc.title)


  val count = db.countMyDocuments()
  println ("Total: " + count)
```

User code **cannot**:

- Access `userId` or `process.argv`
- Write raw SQL
- Bypass `owner_id` filtering
- Access other users' documents
- Call Node.js APIs directly

## Database Schema

```sql
CREATE TABLE documents (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  owner_id INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
```

**Sample data:**

- User 1 (Alice): 3 documents
- User 2 (Bob): 2 documents
- User 3 (Carol): 2 documents

## Requirements

- **Node.js v22.5.0 or higher** (for built-in `node:sqlite` module)

Check your Node.js version:

```bash
node -v  # Should be v22.5.0 or higher
```

## Setup and Running

### Build and run the demo

```bash
demos/data-table/build.sh
```

This script will:

1. Compile the Database API, Runtime, and User Application
2. Initialize the database with sample data
3. Run the demo as three different users (Alice, Bob, Carol)

### Manual execution

If you want to run the compiled app manually:

```bash
# Initialize database
node demos/data-table/init-db.js demos/data-table/database.db

# Run as different users
node demos/data-table/out/app.js 1 demos/data-table/database.db  # Alice
node demos/data-table/out/app.js 2 demos/data-table/database.db  # Bob
node demos/data-table/out/app.js 3 demos/data-table/database.db  # Carol
```


## Explanation of Build Process

### Stage 1: Compile Database API

```bash
bin/jo build-lib DatabaseAPI.jo -d out/api
```

Creates type definitions for `Document`, `DB`, and `param db`.

### Stage 2: Compile Runtime

```bash
bin/jo build-lib Runtime.jo \
  -lib libs/runtime-js:out/api \
  -d out/runtime
```

Compiles the trusted runtime with SQLite access.

### Stage 3: Compile User Application

```bash
bin/jo build -js \
  -link jo.main=DatabaseRuntime.platformMain \
  -link DatabaseAPI.analyzeDocuments=UserApp.analyzeDocuments \
  -lib demos/data-table/out/api \
  -runtime demos/data-table/out/runtime \
  UserApp.jo \
  -o out/app.js
```

Links user code to runtime entry points.

## How Row-Level Security Works

1. **Runtime reads userId and dbPath from argv**
   ```jo
   val userId = js "parseInt(process.argv[2])"
   val dbPath = js "process.argv[3]"
   ```

2. **Runtime opens database and creates SecureDB instance**
   ```jo
   val dbHandle = js "new DatabaseSync(dbPath)"

   // Create secure database instance with captured userId
   val secureDb = new SecureDB(userId, dbHandle)

   // Provide db implementation via context parameter
   DatabaseAPI.analyzeDocuments with DatabaseAPI.db = secureDb
   ```

   The `SecureDB` class captures `userId` in its constructor and implements the `DB` interface via `view DatabaseAPI.DB`. Each method delegates to the implementation functions, passing the captured `userId`.

3. **User code calls db methods**
   ```jo
   val docs = db.queryMyDocuments()  // Calls SecureDB method with captured userId
   ```

4. **SQL automatically filtered**
   ```sql
   SELECT * FROM documents WHERE owner_id = 1  -- userId from runtime
   ```

## Why This is Secure

Even if `UserApp.jo` is **malicious**, it cannot:

1. ❌ Access other users' documents - `userId` is hidden
2. ❌ Write raw SQL - No `js` intrinsic access
3. ❌ Modify `userId` - Captured in runtime closures
4. ❌ Bypass filtering - All queries go through runtime
5. ❌ Call Node.js directly - No access to `process` or `require`

The **compiler enforces** that user code can only access capabilities explicitly provided via context parameters.

## Key Takeaway

Jo's capability system enables **application-level row-level security** where:

- Arguments (like `userId` and `dbPath`) are hidden from untrusted code
- Context parameters provide scoped, filtered database access
- Type system prevents bypassing security controls
- Compiler verifies all access is authorized

This demonstrates how language-level capabilities can enforce fine-grained security policies that are impossible to bypass, even with malicious user code.
