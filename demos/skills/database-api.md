# Database API Reference

The `DatabaseAPI` module provides a type-safe query DSL for working with a SQLite document database. Row-level security is automatically enforced — all queries are scoped to the current user.

## Imports

```jo
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*
```

## Data Types

```jo
// A document row from the database
class Document(
  id: Int,
  title: String,
  content: String,
  ownerId: Int,
  createdAt: String,   // e.g. "2024-01-15"
  draft: Bool
)

// Column references (use these in conditions and ordering)
union Column = Title | Content | OwnerId | CreatedAt | Draft

// Updateable columns (id, owner_id, created_at cannot be updated)
type UpdateColumn = Title | Content | Draft

// Sort order
union SortOrder = Asc | Desc
union OrderBy = NoOrder | Order(col: Column, order: SortOrder)

// Filter condition
union Cond = All | Eq | Neq | Like | Gt | Lt | Gte | Lte | Between
           | Null | NotNull | In | And | Or | Not
```

## The `db` Context Parameter

All queries go through the `db` context parameter (provided by the runtime):

```jo
// Signature of analyzeDocuments (your entry point)
def analyzeDocuments(): Unit receives stdout, db
```

## DB Interface Methods

```jo
// Query documents with optional filtering, ordering, pagination
db.query(condition: Cond): List[Document]
  // context: ordering (default NoOrder), limit (default 100), offset (default 0)

// Count matching documents
db.count(condition: Cond): Int

// Get a specific document by ID (None if not owned by current user)
db.getById(id: Int): Option[Document]

// Update documents matching condition; returns number of rows updated
db.update(condition: Cond, updates: List[FieldUpdate]): Int

// Update a specific document by ID; returns true if updated
db.updateById(id: Int, updates: List[FieldUpdate]): Bool

// Delete documents matching condition; returns number of rows deleted
db.delete(condition: Cond): Int

// Delete a specific document by ID; returns true if deleted
db.deleteById(id: Int): Bool
```

## QueryDSL Operators

### Filter Conditions

```jo
Title == "Report"              // Eq: exact match
Title != "Draft"               // Neq: inequality
Title matches "%report%"       // Like: SQL LIKE pattern (% = wildcard)
CreatedAt > "2024-01-01"       // Gt: greater than
CreatedAt < "2024-12-31"       // Lt: less than
CreatedAt >= "2024-01-01"      // Gte: greater or equal
CreatedAt <= "2024-12-31"      // Lte: less or equal
Draft == true                  // Bool comparison
OwnerId == 1                   // Int comparison

// Null checks
isNull(Content)
isNotNull(Title)

// Range check
CreatedAt between ("2024-01-01", "2024-06-30")

// Membership
Title isIn [str("Report"), str("Plan")]

// Logical
(Title matches "%Q4%") && (Draft == false)     // AND
(Draft == true) || (Title matches "%Urgent%")  // OR
!(Draft == true)                               // NOT

// Match all documents
All
```

### Ordering and Pagination

```jo
// Use 'with' syntax to supply context parameters
val docs = db.query(All) with ordering = asc(CreatedAt)
val docs = db.query(All) with ordering = desc(CreatedAt)
val docs = db.query(All) with limit = 10
val docs = db.query(All) with ordering = desc(CreatedAt), limit = 5
val docs = db.query(All) with ordering = asc(Title), limit = 20, offset = 10
```

### Field Updates

```jo
// := operator creates a FieldUpdate
Title := "New Title"           // update title
Content := "New Content"       // update content
Draft := false                 // mark as published

// Pass a list of updates
db.update(Draft == true, [Draft := false])
db.updateById(42, [Title := "Revised", Draft := false])
```

## Full Example

```jo
namespace UserTask
import jo.IO.stdout
import DatabaseAPI.*
import DatabaseAPI.QueryDSL.*

def analyzeDocuments(): Unit receives stdout, db =
  // Count all documents
  val total = db.count(All)
  println ("Total documents: " + total)

  // Query draft documents, newest first
  val drafts = db.query(Draft == true) with ordering = desc(CreatedAt)
  println ("Draft documents:")
  for doc in drafts do
    println ("  [" + doc.id + "] " + doc.title + " (" + doc.createdAt + ")")

  // Find documents with "Report" in title
  val reports = db.query(Title matches "%Report%")
  println ("Reports found: " + reports.size)

  // Mark all drafts as published
  val updated = db.update(Draft == true, [Draft := false])
  println ("Published " + updated + " documents")

  // Get most recent 3 documents
  val recent = db.query(All) with ordering = desc(CreatedAt), limit = 3
  for doc in recent do
    println (doc.title + " - " + doc.createdAt)
```

## Notes

- All queries are automatically scoped to the current user (row-level security).
- `Draft` is stored as an integer in SQLite (0/1); the API exposes it as `Bool`.
- `createdAt` is a string in `"YYYY-MM-DD"` format — string comparison works for date ordering.
- `limit` defaults to 100 if not specified.
