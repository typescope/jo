# Jo's Solution

Jo uses capability-based security integrated into the language's type system.

## Capability-Based Security

Functions can only access resources explicitly passed as parameters:

```jo
param database: Database
param userId: String

def getOrders(): List[Order] =
  database.query("SELECT * FROM orders")
```

## Technical Properties

**Zero capabilities by default** - Functions start with no access to external resources.

**Explicit capabilities** - All resource access must be declared as parameters.

**Static verification** - Security boundaries are checked at compile time.

**No runtime overhead** - Security enforcement uses the type system.

## Implementation

**Context parameters** - Resources are passed through the context parameter system.

**Effect system** - Required capabilities are tracked in function signatures.

**Type safety** - The type system prevents unauthorized resource access.

## Security Properties

**Confinement** - Code cannot access resources not explicitly provided.

**Auditability** - Resource access is statically determinable from function signatures.

**Composability** - Security properties are preserved when combining functions.