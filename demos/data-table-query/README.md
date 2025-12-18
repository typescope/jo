# Data Table with Query DSL and Full CRUD

This demo extends the basic data-table demo with a **flexible query DSL** for expressing custom filter conditions with optional ordering and pagination, plus **UPDATE and DELETE operations**. It demonstrates how to provide full CRUD capabilities while maintaining row-level security guarantees using **context parameters with defaults**.

## Key Concept

User code can build custom filter conditions and updates using a type-safe DSL:

**Query DSL:**

- **Column references** - Typed columns from schema (`Title`, `CreatedAt`)
- **Comparison operators** - `==`, `matches`, `>`, `<`, `>=`, `<=`
- **Logical operators** - `&&` (AND), `||` (OR), `!` (NOT)
- **Optional context parameters** - `ordering`, `limit`, `offset` with sensible defaults

**Update DSL:**

- **Updateable columns** - Only mutable fields (`Title`, `Content`)
- **Update operator** - `:=` for building field updates
- **Atomic updates** - Multiple fields updated in single transaction

**Security:**

- **Automatic owner_id filtering** - Runtime always includes `WHERE owner_id = ?`
- **Type-safe field restrictions** - Cannot update immutable fields (`id`, `owner_id`, `created_at`)

The runtime translates DSL expressions to parameterized SQL, preventing injection while allowing flexible operations.

## Architecture

```
┌──────────────────┐
│   UserApp.jo     │  UNTRUSTED: Builds queries and updates
│                  │  - Query: db.query(Title matches "%report%")
│                  │  - Update: db.update(All, [Title := "New"])
│                  │  - Delete: db.delete(Title matches "%draft%")
└────────┬─────────┘
         │ receives db
         ▼
┌──────────────────┐
│ DatabaseAPI.jo   │  Interface: CRUD DSL types
│                  │  - class Document(...)
│                  │  - interface DB
│  param db: DB    │  - union Cond = All | Eq | Like | ...
│  param ordering  │  - type UpdateColumn = Title | Content
│  param limit     │  - class FieldUpdate(field, value)
│  param offset    │  - Infix operators: matches, :=, &&, ||
└────────┬─────────┘
         │ provided by
         ▼
┌──────────────────┐
│ Runtime.jo       │  TRUSTED: SQL generation + security
│                  │  - class SecureDB(userId, dbHandle)
│ CRUD operations  │  - view DatabaseAPI.DB
│   query          │  - condToSQL: condition -> SQL
│   update         │  - updatesToSQL: updates -> SET clause
│   delete         │  - Always adds: WHERE owner_id = ?
│                  │  - Parameterizes all values
└──────────────────┘
```

## CRUD Examples

### READ: Query all documents
```jo
val docs = db.query(All)
```
```sql
SELECT * FROM documents WHERE owner_id = ? AND (1=1) LIMIT 100
```

### READ: Query with filter and ordering
```jo
val reports = db.query(Title matches "%Report%") with
  ordering = desc CreatedAt,
  limit = 5
```
```sql
SELECT * FROM documents
WHERE owner_id = ? AND (title LIKE ?)
ORDER BY created_at DESC LIMIT 5
```

### UPDATE: Single field by ID
```jo
db.updateById(1, [Title := "New Title"])
```
```sql
UPDATE documents SET title = ? WHERE id = ? AND owner_id = ?
```

### UPDATE: Multiple fields by ID
```jo
db.updateById(1, [
  Title := "New Title",
  Content := "New Content",
  Draft := false
])
```
```sql
UPDATE documents SET title = ?, content = ?, draft = ?
WHERE id = ? AND owner_id = ?
```

### UPDATE: Bulk update with condition
```jo
db.update(Draft == true, [Draft := false])
```
```sql
UPDATE documents SET draft = ?
WHERE owner_id = ? AND (draft = ?)
```

### DELETE: By ID
```jo
db.deleteById(42)
```
```sql
DELETE FROM documents WHERE id = ? AND owner_id = ?
```

### DELETE: By condition
```jo
db.delete((Title matches "%Temp%") || (CreatedAt < "2024-01-01"))
```
```sql
DELETE FROM documents
WHERE owner_id = ? AND ((title LIKE ?) OR (created_at < ?))
```

## Files

### DatabaseAPI.jo

Defines the CRUD DSL types, operators, and context parameters:

```jo
// Column ADT - all columns in the table
union Column = Title | Content | OwnerId | CreatedAt | Draft

// Updateable columns - subset for updates (type-safe!)
type UpdateColumn = Title | Content | Draft

// Value types for query parameters
union Value = IntValue(v: Int) | StringValue(v: String) | BoolValue(v: Bool)

// Field update for UPDATE operations
class FieldUpdate(field: UpdateColumn, value: Value)

// Sort order for ORDER BY clauses
union SortOrder = Asc | Desc

// Order specification
union OrderBy = NoOrder | Order(col: Column, order: SortOrder)

// Filter condition ADT
union Cond =
    All
  | Eq(col: Column, value: Value)
  | Neq(col: Column, value: Value)
  | Like(col: Column, pat: String)
  | Gt(col: Column, value: Value)
  | Lt(col: Column, value: Value)
  | Gte(col: Column, value: Value)
  | Lte(col: Column, value: Value)
  | Between(col: Column, low: Value, high: Value)
  | Null(col: Column)
  | NotNull(col: Column)
  | In(col: Column, values: List[Value])
  | And(left: Cond, right: Cond)
  | Or(left: Cond, right: Cond)
  | Not(cond: Cond)

// DB interface with full CRUD operations
interface DB
  // Read
  def query(condition: Cond): List[Document] receives ordering, limit, offset
  def count(condition: Cond): Int
  def getById(id: Int): Option[Document]

  // Update
  def update(condition: Cond, updates: List[FieldUpdate]): Int
  def updateById(id: Int, updates: List[FieldUpdate]): Bool

  // Delete
  def delete(condition: Cond): Int
  def deleteById(id: Int): Bool
end

// Context parameters with defaults
param db: DB
param ordering: OrderBy = NoOrder
param limit: Int = 100
param offset: Int = 0

// Duck type for automatic value conversion
type ValueLike = like Value with [str, int, bool]

// Infix operators for DSL
section QueryDSL
  // Query operators
  def (col: Column) matches (pat: String): Cond = Like(col, pat)
  def (left: Cond) && (right: Cond): Cond = And(left, right)
  // ...

  // Update operator
  def (field: UpdateColumn) := (value: ValueLike): FieldUpdate =
    FieldUpdate(field, value)
end
```

### Runtime.jo

Key implementation functions with security enforcement:

```jo
section Impl
  param dbHandle: Any

  // Translate condition AST to SQL
  def condToSQL(cond: Cond): QueryParts =
    match cond
      case All => QueryParts("1=1", List.empty)
      case Like col pat => QueryParts(columnName(col) + " LIKE ?", [StringValue(pat)])
      case And left right =>
        val leftParts = condToSQL(left)
        val rightParts = condToSQL(right)
        QueryParts("(" + leftParts.sql + " AND " + rightParts.sql + ")",
                   leftParts.params ++ rightParts.params)
      // ...
    end

  // Translate updates to SET clause
  def updatesToSQL(updates: List[FieldUpdate]): QueryParts =
    // Builds "title = ?, content = ?" with corresponding params
    // ...

  // QUERY with owner_id enforcement
  def queryImpl(userId: Int, condition: Cond): List[Document]
      receives dbHandle, ordering, limit, offset =
    val condParts = condToSQL(condition)
    val whereClause = "owner_id = ? AND (" + condParts.sql + ")"  // Security!
    // ... build and execute SQL

  // UPDATE with owner_id enforcement
  def updateImpl(userId: Int, condition: Cond, updates: List[FieldUpdate]): Int
      receives dbHandle =
    val whereClause = "owner_id = ? AND (" + condParts.sql + ")"  // Security!
    val sql = "UPDATE documents SET " + updateParts.sql + " WHERE " + whereClause
    // ... execute and return changed count

  // DELETE with owner_id enforcement
  def deleteImpl(userId: Int, condition: Cond): Int receives dbHandle =
    val whereClause = "owner_id = ? AND (" + condParts.sql + ")"  // Security!
    // ... execute and return deleted count
end

class SecureDB(userId: Int, dbHandle: Any)
  def query(condition: Cond): List[Document] receives ordering, limit, offset =
    Impl.queryImpl(userId, condition) with Impl.dbHandle = dbHandle

  def count(condition: Cond): Int =
    Impl.countImpl(userId, condition) with Impl.dbHandle = dbHandle

  def getById(id: Int): Option[Document] =
    Impl.getByIdImpl(userId, id) with Impl.dbHandle = dbHandle

  def update(condition: Cond, updates: List[FieldUpdate]): Int =
    Impl.updateImpl(userId, condition, updates) with Impl.dbHandle = dbHandle

  def updateById(id: Int, updates: List[FieldUpdate]): Bool =
    Impl.updateByIdImpl(userId, id, updates) with Impl.dbHandle = dbHandle

  def delete(condition: Cond): Int =
    Impl.deleteImpl(userId, condition) with Impl.dbHandle = dbHandle

  def deleteById(id: Int): Bool =
    Impl.deleteByIdImpl(userId, id) with Impl.dbHandle = dbHandle

  view DatabaseAPI.DB
end
```

**Security guarantee**: All operations always include `WHERE owner_id = ?` to enforce row-level security. The `class SecureDB` captures `userId` and `dbHandle` and implements `DB` interface via `view DatabaseAPI.DB`.

### UserApp.jo

Demonstrates CRUD operations:

```jo
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*

def analyzeDocuments: Unit receives stdout, db =
  // READ: Query with filter and ordering
  val reports = db.query(Title matches "%Report%") with
    ordering = desc CreatedAt,
    limit = 5

  // UPDATE: Single field by ID
  db.updateById(1, [Title := "New Title"])

  // UPDATE: Multiple fields by ID
  db.updateById(1, [
    Title := "New Title",
    Content := "New Content",
    Draft := false
  ])

  // UPDATE: Bulk update - publish all drafts
  db.update(Draft == true, [Draft := false])

  // DELETE: By ID
  db.deleteById(42)

  // DELETE: Delete all drafts
  db.delete(Draft == true)

  // COUNT: After operations
  val remaining = db.count(All)
```

## Requirements

- **Node.js v22.5.0 or higher** (for built-in `node:sqlite` module)

Check your Node.js version:

```bash
node -v  # Should be v22.5.0 or higher
```

## Setup and Running

### Build and run the demo

```bash
demos/data-table-query/build.sh
```

This script will:

1. Compile the Database API with query DSL
2. Compile the Runtime with SQL generation
3. Compile the User Application
4. Initialize the database (reuses data-table demo's database)
5. Run the demo as three different users (Alice, Bob, Carol)

### Manual execution

If you want to run the compiled app manually:

```bash
# Make sure database is initialized
node demos/data-table-query/init-db.js demos/data-table-query/database.db

# Run as different users
node demos/data-table-query/out/app.js 1 demos/data-table-query/database.db  # Alice
node demos/data-table-query/out/app.js 2 demos/data-table-query/database.db  # Bob
node demos/data-table-query/out/app.js 3 demos/data-table-query/database.db  # Carol
```

## Security Properties

The CRUD DSL maintains strong security guarantees:

1. ✅ **Row-level security** - All operations scoped to `owner_id`
2. ✅ **SQL injection proof** - All values parameterized via `?` placeholders
3. ✅ **Type-safe field updates** - Cannot update immutable fields (`id`, `owner_id`, `created_at`)
4. ✅ **SQL-level operations** - Efficient for large tables (no in-memory filtering)
5. ✅ **Composable** - Build complex queries and updates from simple building blocks
6. ✅ **No bypass possible** - Even malicious user code cannot skip security checks

User code **cannot**:

- ❌ Access/modify/delete other users' documents (owner_id always enforced)
- ❌ Update immutable fields (type system prevents it)
- ❌ Write raw SQL (no `js` intrinsic access)
- ❌ Inject SQL (all values parameterized)
- ❌ Bypass security (runtime controls SQL generation)


## Design Highlights

### Duck Types for Clean DSL Syntax

The DSL uses **duck types** to automatically convert primitive values to the `Value` type:

```jo
// Adapter functions
def str(s: String): Value = StringValue(s)
def int(n: Int): Value = IntValue(n)
def bool(b: Bool): Value = BoolValue(b)

// Duck type alias for values that can be converted
type ValueLike = like Value with [str, int, bool]

// DSL operators accept ValueLike duck type
def (col: Column) == (v: ValueLike): Cond = Eq(col, v)
def (field: UpdateColumn) := (value: ValueLike): FieldUpdate = ...
```

This allows natural syntax without explicit conversions:

```jo
// Clean syntax - adapters applied automatically
Draft == true
Title := "New Title"
CreatedAt < "2024-01-01"

// Instead of verbose manual conversion
Draft == bool(true)
Title := str("New Title")
CreatedAt < str("2024-01-01")
```

Benefits:

- **Readable** - DSL looks like natural comparisons and assignments
- **Type-safe** - Compiler ensures correct adapter is used based on value type
- **Reusable** - `ValueLike` type alias used throughout the DSL
- **No runtime overhead** - Adapters resolved at compile time

### Type-Safe Updateable Columns

Using type aliases to define updateable column subset:

```jo
union Column = Title | Content | OwnerId | CreatedAt
type UpdateColumn = Title | Content  // Only mutable fields!
```

Benefits:

- **O(n) scalability** - Adding columns scales linearly
- **Compile-time safety** - Cannot update `OwnerId` or `CreatedAt`
- **Type system enforcement** - No runtime checks needed
- **Self-documenting** - Clear which fields are mutable

### Update Operator `:=`

Single, clean operator for building field updates:

```jo
def (field: UpdateColumn) := (value: Value): FieldUpdate =
  FieldUpdate(field, value)
```

Usage:
```jo
[Title := "New Title", Content := "New Content"]
```

Benefits:

- **One obvious way** - No alternative syntax confusion
- **Compositional** - Combine multiple field updates in a list
- **Readable** - Clear intent of assignment

### Context Parameters with Defaults

Context parameters enable optional customization without API explosion:

```jo
param ordering: OrderBy = NoOrder
param limit: Int = 100
param offset: Int = 0

// Use defaults
val docs = db.query(All)

// Override selectively
val orderedDocs = db.query(All) with ordering = desc CreatedAt
```

### Pattern Matching for SQL Translation

The runtime uses pattern matching for clean AST translation:

```jo
def condToSQL(cond: Cond): QueryParts =
  match cond
    case All => QueryParts("1=1", List.empty)
    case And left right => /* recurse and combine */
    // ...
  end

def updatesToSQL(updates: List[FieldUpdate]): QueryParts =
  // Builds "field1 = ?, field2 = ?" from update list
```

This is type-safe, maintainable, and easy to extend.

## Key Takeaway

This demo shows how to provide **full CRUD capabilities with SQL-level operations** while maintaining **row-level security**:

- **Complete CRUD** - Read, Update, Delete with type-safe DSL
- **Type-safe updates** - `UpdateColumn` subset prevents immutable field modifications
- **Scalable design** - O(n) growth for tables with many columns
- **Composable operators** - Build complex queries and updates from simple building blocks
- **Context parameters** - Optional customization without API explosion
- **SQL-level efficiency** - No in-memory filtering, database does the work
- **Security enforcement** - Runtime always injects `owner_id` check for all operations
- **No bypass possible** - Compiler and type system enforce security

The combination of Jo's language features (ADTs, duck types, type aliases, pattern matching, infix operators, context parameters) enables building a safe and flexible CRUD system that would be difficult to achieve in traditional languages. The `type UpdateColumn = Title | Content` pattern provides type-safe field restriction at compile time, scaling linearly with table size. Duck types like `ValueLike` enable clean DSL syntax with automatic type conversions.
