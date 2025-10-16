# Data Table Access Control with SQLite

This demo demonstrates **row-level security** in Jo, where different users can only access their own database rows. The demo shows how Jo's capability system enforces fine-grained access control at the application level.

## Key Concept

The `userId` is passed as a **command-line argument** (NOT a context parameter) and is only accessible to the trusted runtime. The runtime:
1. Reads `userId` from `process.argv[2]`
2. Captures it in closures when providing the `db` context parameter
3. Automatically injects `WHERE owner_id = ?` into all SQL queries

User code **never sees the userId** and cannot bypass the filtering.

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
│ DatabaseAPI.jo   │  Interface: types + param
│                  │  - type DB = { ... }
│ param db: DB     │  - type Document = { ... }
└────────┬─────────┘
         │ provided by
         ▼
┌──────────────────┐
│ Runtime.jo       │  TRUSTED: Enforces security
│                  │  - Reads userId from argv
│ def platformMain │  - Captures in db closures
│   userId = argv  │  - Auto-filters queries
│   userApp with   │  - User CANNOT access userId
│     db = { ... } │
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

Defines types and context parameter:

```jo
type Document = {
  val id: Int
  val title: String
  val content: String
  val ownerId: Int
  val createdAt: String
}

type DB = {
  def queryMyDocuments(): List[Document]
  def getMyDocument(id: Int): Option[Document]
  def countMyDocuments(): Int
}

param db: DB
```

### Runtime.jo (Trusted)

Reads `userId` from argv and provides `db`:

```jo
def platformMain: Unit receives stdout =
  // Read userId from command line - ABORT if missing
  val userIdStr = js "process.argv[2]"
  if userIdStr == js "undefined" then
    abort "Missing userId argument"

  val userId = js "parseInt(process.argv[2])"

  // Provide db with userId captured in closures
  analyzeDocuments with
    db = {
      def queryMyDocuments(): List[Document] =
        // userId captured here - user cannot access it!
        val sql = "SELECT * FROM documents WHERE owner_id = ?"
        execQuery(sql, userId)
        // ...
    }
```

**Key technique**: `userId` is captured in the closure of each `db` method. User code calls these methods but cannot inspect or modify `userId`.

### UserApp.jo (Untrusted)

Uses database through typed interface:

```jo
def analyzeDocuments: Unit receives stdout, db =
  // User code has NO ACCESS to userId!
  // Can only call db methods which are already scoped

  val docs = db.queryMyDocuments()  // Automatically filtered!
  docs.foreach doc =>
    println (doc.title)


  val count = db.countMyDocuments()
  println ("Total: " + (intToStr count))
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

## Setup

### 1. Initialize database

```bash
node demos/data-table/init-db.js
```

This creates `database.db` with sample data.

### 2. Build the demo

```bash
demos/data-table/build.sh
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
  -no-detect-main \
  -link jo.Main.main=DatabaseRuntime.platformMain \
  -link DatabaseAPI.analyzeDocuments=UserApp.analyzeDocuments \
  -lib demos/data-table/out/api \
  -runtime demos/data-table/out/runtime \
  UserApp.jo \
  -o out/app.js
```

Links user code to runtime entry points.

## How Row-Level Security Works

1. **Runtime reads userId from argv**
   ```jo
   val userId = js "parseInt(process.argv[2])"
   ```

2. **Runtime captures userId in db closures**
   ```jo
   analyzeDocuments with
     db = {
       def queryMyDocuments(): List[Document] =
         // userId is captured in this closure
         val sql = "SELECT * FROM documents WHERE owner_id = ?"
         execQuery(sql, userId)  // userId from closure, not user code!
     }
   ```

3. **User code calls db methods**
   ```jo
   val docs = db.queryMyDocuments()  // Calls closure with captured userId
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

- Arguments (like `userId`) are hidden from untrusted code
- Context parameters provide scoped, filtered database access
- Type system prevents bypassing security controls
- Compiler verifies all access is authorized

This demonstrates how language-level capabilities can enforce fine-grained security policies that are impossible to bypass, even with malicious user code.
