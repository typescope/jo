# Data Table with Query DSL

This demo extends the basic data-table demo with a **flexible query DSL** for expressing custom filter conditions with optional ordering and pagination. It demonstrates how to provide SQL-level filtering while maintaining row-level security guarantees using **context parameters with defaults**.

## Key Concept

User code can build custom filter conditions using a type-safe DSL with atomic building blocks:

- **Column references** - Typed columns from schema (`Title`, `CreatedAt`)
- **Comparison operators** - `==`, `like`, `>`, `<`, `>=`, `<=`
- **Logical operators** - `&&` (AND), `||` (OR), `!` (NOT)
- **Automatic owner_id filtering** - Runtime always ANDs user conditions with `WHERE owner_id = ?`
- **Optional context parameters** - `ordering`, `limit`, `offset` with sensible defaults
- **Unified query method** - Single `query(condition)` method handles all query scenarios

The runtime translates the condition AST to parameterized SQL, preventing injection while allowing flexible queries.

## Architecture

```
┌──────────────────┐
│   UserApp.jo     │  UNTRUSTED: Builds query conditions
│                  │  - Uses DSL: Title like "%report%"
│                  │  - Calls: db.query(condition)
│                  │  - Optionally: with ordering = desc CreatedAt
└────────┬─────────┘
         │ receives db
         ▼
┌──────────────────┐
│ DatabaseAPI.jo   │  Interface: Query DSL types & context params
│                  │  - data Cond = All | Eq | Like | ...
│                  │  - data Column = Title | Content | ...
│                  │  - data OrderBy = NoOrder | Order(col, order)
│  param db: DB    │  - Infix operators for DSL
│  param ordering  │  - Context params with defaults:
│  param limit     │    ordering = NoOrder, limit = 100, offset = 0
│  param offset    │
└────────┬─────────┘
         │ provided by
         ▼
┌──────────────────┐
│ Runtime.jo       │  TRUSTED: SQL generation
│                  │  - condToSQL(cond) -> SQL + params
│ def query        │  - Always adds: WHERE owner_id = ?
│   condToSQL      │  - Parameterizes all values
│   owner_id AND   │  - Handles ordering, limit, offset
│                  │  - User CANNOT bypass security
└──────────────────┘
```

## Query DSL Examples

### Query all documents
```jo
val docs = db.query(All)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (1=1) LIMIT 100
-- Parameters: [userId]
```

### Simple LIKE query
```jo
val reports = db.query(Title like "%Report%")
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (title LIKE ?) LIMIT 100
-- Parameters: [userId, "%Report%"]
```

### Date comparison
```jo
val recent = db.query(CreatedAt > str("2024-01-15"))
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (created_at > ?) LIMIT 100
-- Parameters: [userId, "2024-01-15"]
```

### Compound AND query
```jo
val recentReports = db.query(
  (Title like "%Report%") && (CreatedAt > str("2024-01-10"))
)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND ((title LIKE ?) AND (created_at > ?)) LIMIT 100
-- Parameters: [userId, "%Report%", "2024-01-10"]
```

### Compound OR query
```jo
val budgetOrPlan = db.query(
  (Title like "%Budget%") || (Title like "%Plan%")
)
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND ((title LIKE ?) OR (title LIKE ?)) LIMIT 100
-- Parameters: [userId, "%Budget%", "%Plan%"]
```

### Query with ordering
```jo
val orderedDocs = db.query(All) with ordering = desc CreatedAt
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (1=1) ORDER BY created_at DESC LIMIT 100
-- Parameters: [userId]
```

### Query with pagination
```jo
val page1 = db.query(All) with limit = 10, offset = 0
```

Translates to:
```sql
SELECT * FROM documents WHERE owner_id = ? AND (1=1) LIMIT 10 OFFSET 0
-- Parameters: [userId]
```

### Full-featured query (filter + ordering + limit)
```jo
val results = db.query(Title like "%Report%") with
  ordering = desc CreatedAt,
  limit = 5,
  offset = 0
```

Translates to:
```sql
SELECT * FROM documents
WHERE owner_id = ? AND (title LIKE ?)
ORDER BY created_at DESC
LIMIT 5 OFFSET 0
-- Parameters: [userId, "%Report%"]
```

## Files

### DatabaseAPI.jo

Defines the query DSL types, operators, and context parameters:

```jo
// Column ADT - only valid columns can be constructed
data Column =
    Title
  | Content
  | OwnerId
  | CreatedAt

// Sort order for ORDER BY
data SortOrder = Asc | Desc

// OrderBy specification with NoOrder default
data OrderBy = NoOrder | Order(col: Column, order: SortOrder)

// Filter condition ADT
data Cond =
    All  // No filtering
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

// Unified DB interface with context parameters
type DB = {
  def query(condition: Cond): List[Document] receives ordering, limit, offset
  def count(condition: Cond): Int
  def getMyDocument(id: Int): Option[Document]
}

// Context parameters with sensible defaults
param db: DB
param ordering: OrderBy = NoOrder
param limit: Int = 100
param offset: Int = 0

// Infix operators for DSL
section QueryDSL
  def (col: Column) == (v: Value): Cond = Eq(col, v)
  def (col: Column) != (v: Value): Cond = Neq(col, v)
  def (col: Column) like (pat: String): Cond = Like(col, pat)
  def (col: Column) > (v: Value): Cond = Gt(col, v)
  def (col: Column) < (v: Value): Cond = Lt(col, v)
  def (col: Column) >= (v: Value): Cond = Gte(col, v)
  def (col: Column) <= (v: Value): Cond = Lte(col, v)

  def (left: Cond) && (right: Cond): Cond = And(left, right)
  def (left: Cond) || (right: Cond): Cond = Or(left, right)
  def !(cond: Cond): Cond = Not(cond)

  def str(s: String): Value = StringValue(s)
  def int(n: Int): Value = IntValue(n)

  def (col: Column) in (values: List[Value]): Cond = In(col, values)
  def (col: Column) between (low: Value, high: Value): Cond = Between(col, low, high)

  def asc(col: Column): OrderBy = Order(col, Asc)
  def desc(col: Column): OrderBy = Order(col, Desc)
end
```

### Runtime.jo

Implementation functions organized in `section Impl` with context parameter for database handle. Context parameters `ordering`, `limit`, `offset` are imported from DatabaseAPI and flow automatically:

```jo
namespace DatabaseRuntime

import jo.runtime.JS.js
import DatabaseAPI.*
import DatabaseAPI.ordering
import DatabaseAPI.limit
import DatabaseAPI.offset

section Impl
  param dbHandle: Any

  def condToSQL(cond: Cond): QueryParts =
    match cond
      case All =>
        QueryParts("1=1", List.empty)

      case Eq col value =>
        QueryParts(columnName(col) + " = ?", [value])

      case Like col pat =>
        QueryParts(columnName(col) + " LIKE ?", [str(pat)])

      case And left right =>
        val leftParts = condToSQL(left)
        val rightParts = condToSQL(right)
        val combinedSQL = "(" + leftParts.sql + " AND " + rightParts.sql + ")"
        val combinedParams = leftParts.params ++ rightParts.params
        QueryParts(combinedSQL, combinedParams)
      // ... other cases
    end

  def buildOrderByClause(ordering: OrderBy): String =
    match ordering
      case NoOrder => ""
      case Order col order =>
        var clause = " ORDER BY " + columnName(col)
        match order
          case Asc => clause = clause + " ASC"
          case Desc => clause = clause + " DESC"
        end
        clause
    end

  // queryImpl receives context parameters and uses them directly
  def queryImpl(userId: Int, condition: Cond): List[Document]
      receives dbHandle, ordering, limit, offset
    =

    val condParts = condToSQL(condition)
    // Always AND with owner_id check - user cannot bypass this!
    val whereClause = "owner_id = ? AND (" + condParts.sql + ")"
    val params = condParts.params.prepend(IntValue(userId))

    val orderByClause = buildOrderByClause(ordering)
    val limitOffsetClause = buildLimitOffsetClause(limit, offset)

    val sql = "SELECT * FROM documents WHERE " + whereClause +
              orderByClause + limitOffsetClause
    // ... execute and build document list
end

def platformMain: Unit receives stdout =
  val userId = js "parseInt(process.argv[2])"
  val dbHandle = js "new DatabaseSync(dbPath)"

  DatabaseAPI.analyzeDocuments with
    DatabaseAPI.db = {
      def query(condition: Cond): List[Document] receives ordering, limit, offset =
        Impl.queryImpl(userId, condition) with Impl.dbHandle = dbHandle

      def count(condition: Cond): Int =
        Impl.countImpl(userId, condition) with Impl.dbHandle = dbHandle

      def getMyDocument(id: Int): Option[Document] =
        Impl.getMyDocumentImpl(userId, id) with Impl.dbHandle = dbHandle
    }
```

**Security guarantee**: User conditions are always ANDed with `owner_id = ?`.

### UserApp.jo

Demonstrates various query patterns with the unified API:

```jo
import DatabaseAPI.db
import DatabaseAPI.ordering
import DatabaseAPI.limit
import DatabaseAPI.offset
import DatabaseAPI.{Title, Content, CreatedAt, All}
import DatabaseAPI.QueryDSL.*

def analyzeDocuments: Unit receives stdout, db =
  // Query 1: All documents (uses default limit=100)
  val allDocs = db.query(All)

  // Query 2: Simple LIKE
  val reports = db.query(Title like "%Report%")

  // Query 3: Date comparison
  val recent = db.query(CreatedAt > str("2024-01-15"))

  // Query 4: Compound AND
  val recentReports = db.query(
    (Title like "%Report%") && (CreatedAt > str("2024-01-10"))
  )

  // Query 5: Compound OR
  val budgetOrPlan = db.query(
    (Title like "%Budget%") || (Title like "%Plan%")
  )

  // Query 6: With ordering
  val orderedDocs = db.query(All) with ordering = desc CreatedAt

  // Query 7: With pagination
  val page1 = db.query(All) with limit = 2, offset = 0

  // Query 8: Full-featured (filter + ordering + limit)
  val fullQuery = db.query(Title like "%Report%") with
    ordering = desc CreatedAt,
    limit = 2,
    offset = 0

  // Query 9: Count
  val totalCount = db.count(All)
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


## Design Highlights

### Context Parameters with Defaults

The demo showcases Jo's context parameters with default values:

```jo
param ordering: OrderBy = NoOrder
param limit: Int = 100
param offset: Int = 0
```

Benefits:
- **Optional overriding** - Use `with` clause to customize: `db.query(All) with ordering = desc CreatedAt`
- **Sensible defaults** - Most queries use defaults without explicit specification
- **Type-safe** - Compiler validates parameter types
- **Discoverable** - IDE can suggest available context parameters

### Unified Query Method

Instead of multiple methods (`query`, `queryWhere`, `queryOrderBy`, `queryPage`), a single method handles all scenarios:

```jo
def query(condition: Cond): List[Document] receives ordering, limit, offset
```

The `condition` is a **required parameter** (explicit filtering intent), while `ordering`, `limit`, `offset` are **context parameters** (optional customization).

### Atomic Building Blocks

Instead of high-level operations like `TitleContains`, the DSL provides atomic SQL operations:

- `Eq`, `Neq`, `Like`, `Gt`, `Lt`, `Gte`, `Lte` - SQL comparison operators
- `And`, `Or`, `Not` - SQL logical operators
- `In`, `Between`, `Null`, `NotNull` - SQL membership and null checks

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

This demo shows how to provide **flexible SQL-level filtering with ordering and pagination** while maintaining **row-level security**:

- **Query DSL** - Type-safe, composable, and expressive
- **Atomic operators** - Build complex queries from simple building blocks
- **Context parameters with defaults** - Optional customization without API explosion
- **Unified API** - Single `query` method handles all scenarios
- **Schema-driven** - Typed columns provide discoverability
- **SQL generation** - Efficient filtering at database level
- **Security enforcement** - Runtime always injects owner_id check
- **No bypass possible** - Compiler and type system enforce security

The combination of Jo's language features (ADTs, pattern matching, infix operators, context parameters with defaults) enables building a safe and flexible query system that would be difficult to achieve in traditional languages. The context parameters pattern eliminates the need for method overloading or builder patterns while maintaining type safety and clean syntax.
