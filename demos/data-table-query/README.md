# Data Table with Query DSL

This demo extends the basic data-table demo with a **flexible query DSL** for expressing custom filter conditions. It demonstrates how to provide SQL-level filtering while maintaining row-level security guarantees.

## Key Concept

User code can build custom filter conditions using a type-safe DSL with atomic building blocks:

- **Column references** - Typed columns from schema (`table.title`, `table.createdAt`)
- **Comparison operators** - `===`, `like`, `>`, `<`, `>=`, `<=`
- **Logical operators** - `&&` (AND), `||` (OR)
- **Automatic owner_id filtering** - Runtime always ANDs user conditions with `WHERE owner_id = ?`

The runtime translates the condition AST to parameterized SQL, preventing injection while allowing flexible queries.

## Architecture

```
┌──────────────────┐
│   UserApp.jo     │  UNTRUSTED: Builds query conditions
│                  │  - Gets schema via getSchema()
│                  │  - Uses DSL: (table.title like "%report%")
│                  │  - Calls queryWhere(condition)
└────────┬─────────┘
         │ receives
         ▼
┌──────────────────┐
│ DatabaseAPI.jo   │  Interface: Query DSL types
│                  │  - data Cond (filter conditions)
│                  │  - data Column, Table
│                  │  - Infix operators for DSL
│  param db: DB    │  - def (col: Column) like (p: String): Cond
└────────┬─────────┘
         │ provided by
         ▼
┌──────────────────┐
│ Runtime.jo       │  TRUSTED: SQL generation
│                  │  - condToSQL(cond) -> SQL + params
│ def queryWhere   │  - Always adds: WHERE owner_id = ?
│   condToSQL      │  - Parameterizes all values
│   owner_id AND   │  - User CANNOT bypass security
└──────────────────┘
```

## Query DSL Examples

### Simple LIKE query
```jo
val condition = Title like "%Report%"
val docs = db.queryWhere(condition)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (title LIKE ?)
-- Parameters: [userId, "%Report%"]
```

### Date comparison
```jo
val condition = CreatedAt > str("2024-01-15")
val docs = db.queryWhere(condition)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (created_at > ?)
-- Parameters: [userId, "2024-01-15"]
```

### Compound AND query
```jo
val condition =
  (Title like "%Report%") && (CreatedAt > str("2024-01-10"))
val docs = db.queryWhere(condition)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND ((title LIKE ?) AND (created_at > ?))
-- Parameters: [userId, "%Report%", "2024-01-10"]
```

### Compound OR query
```jo
val condition =
  (Title like "%Budget%") || (Title like "%Plan%")
val docs = db.queryWhere(condition)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND ((title LIKE ?) OR (title LIKE ?))
-- Parameters: [userId, "%Budget%", "%Plan%"]
```

### Complex nested query
```jo
val condition =
  ((Title like "%Meeting%") && (CreatedAt > str("2024-01-05")))
  || (Title like "%Urgent%")
val docs = db.queryWhere(condition)
```

Translates to:
```sql
SELECT * FROM documents
WHERE owner_id = ?
  AND (((title LIKE ?) AND (created_at > ?)) OR (title LIKE ?))
-- Parameters: [userId, "%Meeting%", "2024-01-05", "%Urgent%"]
```

## Files

### DatabaseAPI.jo

Defines the query DSL types and operators:

```jo
// Column ADT - only valid columns can be constructed
data Column =
    Title
  | Content
  | OwnerId
  | CreatedAt

// Filter condition ADT
data Cond =
    Eq(col: Column, val: Value)
  | Like(col: Column, pattern: String)
  | Gt(col: Column, val: Value)
  | Lt(col: Column, val: Value)
  | Gte(col: Column, val: Value)
  | Lte(col: Column, val: Value)
  | And(left: Cond, right: Cond)
  | Or(left: Cond, right: Cond)

// Infix operators for DSL
section QueryDSL
  def (col: Column) === (val: Value): Cond = Eq(col, val)
  def (col: Column) like (pattern: String): Cond = Like(col, pattern)
  def (col: Column) > (val: Value): Cond = Gt(col, val)
  // ... more operators

  def (left: Cond) && (right: Cond): Cond = And(left, right)
  def (left: Cond) || (right: Cond): Cond = Or(left, right)
end
```

### Runtime.jo

Implementation functions organized in `section Impl` with context parameter for database handle:

```jo
section Impl
  param dbHandle: Any

  def execQuery(sql: String, params: Any): String receives dbHandle =
    val db = dbHandle
    val stmt = js "db.prepare(sql)"
    val rows = js "stmt.all(...params)"
    rows

  def condToSQL(cond: Cond): QueryParts =
    match cond
      case Eq col val =>
        QueryParts(col.name + " = ?", List(val))

      case Like col pattern =>
        QueryParts(col.name + " LIKE ?", List(StringValue(pattern)))

      case And left right =>
        val leftParts = condToSQL(left)
        val rightParts = condToSQL(right)
        val combinedSQL = "(" + leftParts.sql + " AND " + rightParts.sql + ")"
        val combinedParams = leftParts.params ++ rightParts.params
        QueryParts(combinedSQL, combinedParams)
      // ...
    end

  def queryWhereImpl(userId: Int, condition: Cond): List[Document] receives dbHandle =
    val parts = condToSQL(condition)
    // Always AND with owner_id check - user cannot bypass this!
    val sql = "SELECT * FROM documents WHERE owner_id = ? AND (" + parts.sql + ")"
    val allParams = parts.params.prepend(IntValue(userId))
    val jsParams = paramsToJSArray(allParams)
    val rows = execQuery(sql, jsParams)
    // ... build document list
end

def platformMain: Unit receives stdout =
  val userId = js "parseInt(process.argv[2])"
  val dbHandle = js "new DatabaseSync(dbPath)"

  DatabaseAPI.analyzeDocuments with
    DatabaseAPI.db = {
      def queryWhere(condition: Cond): List[Document] =
        Impl.queryWhereImpl(userId, condition) with Impl.dbHandle = dbHandle
    }
```

**Security guarantee**: User conditions are always ANDed with `owner_id = ?`. Implementation uses context parameters for database handle.

### UserApp.jo

Demonstrates various query patterns:

```jo
import DatabaseAPI.{Title, Content, CreatedAt}
import DatabaseAPI.QueryDSL.{str, int}

def analyzeDocuments: Unit receives stdout, db =
  // Query 1: Simple LIKE
  val reports = db.queryWhere(Title like "%Report%")

  // Query 2: Date comparison
  val recent = db.queryWhere(CreatedAt > str("2024-01-15"))

  // Query 3: Compound AND
  val recentReports = db.queryWhere(
    (Title like "%Report%") && (CreatedAt > str("2024-01-10"))
  )

  // Query 4: Compound OR
  val budgetOrPlan = db.queryWhere(
    (Title like "%Budget%") || (Title like "%Plan%")
  )
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
node demos/data-table/init-db.js demos/data-table/database.db

# Run as different users
node demos/data-table-query/out/app.js 1 demos/data-table/database.db  # Alice
node demos/data-table-query/out/app.js 2 demos/data-table/database.db  # Bob
node demos/data-table-query/out/app.js 3 demos/data-table/database.db  # Carol
```

## Security Properties

The query DSL maintains strong security guarantees:

1. ✅ **SQL-level filtering** - Efficient for large tables (no in-memory filtering)
2. ✅ **Automatic owner_id injection** - Runtime always adds `WHERE owner_id = ?`
3. ✅ **SQL injection proof** - All values parameterized via `?` placeholders
4. ✅ **Type-safe** - Compiler validates condition construction
5. ✅ **Schema-driven** - Typed columns prevent typos and provide IDE support
6. ✅ **Composable** - Build complex queries from simple building blocks
7. ✅ **No bypass possible** - Even malicious user code cannot skip owner_id check

User code **cannot**:

- ❌ Access other users' documents (owner_id always enforced)
- ❌ Write raw SQL (no `js` intrinsic access)
- ❌ Inject SQL (all values parameterized)
- ❌ Access arbitrary columns (schema provides only allowed columns)
- ❌ Bypass security (runtime controls SQL generation)

## Comparison with Basic Data-Table Demo

| Feature | Basic Demo | Query DSL Demo |
|---------|-----------|----------------|
| Query all documents | ✅ | ✅ |
| Get by ID | ✅ | ✅ |
| Custom filters | ❌ | ✅ |
| LIKE patterns | ❌ | ✅ |
| Date comparisons | ❌ | ✅ |
| AND/OR logic | ❌ | ✅ |
| SQL-level filtering | N/A | ✅ |
| Row-level security | ✅ | ✅ |

## Design Highlights

### Atomic Building Blocks

Instead of high-level operations like `TitleContains`, the DSL provides atomic SQL operations:

- `Eq`, `Like`, `Gt`, `Lt`, `Gte`, `Lte` - SQL comparison operators
- `And`, `Or` - SQL logical operators

This makes the DSL:
- More general and composable
- Easier to extend with new operators
- Closer to SQL semantics

### Infix Operators

Jo's support for custom infix operators enables natural query syntax:

```jo
def (col: Column) like (pattern: String): Cond = Like(col, pattern)
def (left: Cond) && (right: Cond): Cond = And(left, right)
```

Usage reads naturally:
```jo
Title like "%Report%" && CreatedAt > str("2024-01-15")
```

### Column ADT for Type Safety

Columns are defined as an ADT, preventing invalid column references:

```jo
data Column =
    Title
  | Content
  | OwnerId
  | CreatedAt
```

Benefits:
- **Type-safe** - Impossible to create invalid columns like `Column("foobar")`
- **Compile-time validation** - Typos caught at compile time
- **Discoverable** - IDE autocomplete suggests valid columns
- **Self-documenting** - Clear what columns exist
- **Direct usage** - Import and use: `import DatabaseAPI.{Title, CreatedAt}`

### Pattern Matching for SQL Generation

The runtime uses pattern matching to recursively translate the condition AST:

```jo
def condToSQL(cond: Cond): QueryParts =
  match cond
    case Cond.Eq col val => /* generate = ? */
    case Cond.And left right => /* recurse and combine */
    // ...
  end
```

This is:
- Clean and maintainable
- Easy to extend with new operators
- Type-safe (exhaustive matching)

## Key Takeaway

This demo shows how to provide **flexible SQL-level filtering** while maintaining **row-level security**:

- **Query DSL** - Type-safe, composable, and expressive
- **Atomic operators** - Build complex queries from simple building blocks
- **Schema-driven** - Typed columns provide discoverability
- **SQL generation** - Efficient filtering at database level
- **Security enforcement** - Runtime always injects owner_id check
- **No bypass possible** - Compiler and type system enforce security

The combination of Jo's language features (ADTs, pattern matching, infix operators, context parameters) enables building a safe and flexible query system that would be difficult to achieve in traditional languages.
