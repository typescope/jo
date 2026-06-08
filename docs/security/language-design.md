# Language Design

This page describes the language facilities that make secure programming practical in Jo. Each section addresses one of the three challenges from [The Security Problem](security-problem.md).

## Eliminating Ambient Authorities

Jo removes all ambient authorities from the language:

- **No global variables** — all state is explicitly passed
- **No FFI in untrusted code** — foreign function interface is restricted to trusted code
- **No reflection** — runtime introspection cannot bypass the type system
- **No control flow effects** — no exceptions, no `setjmp`/`longjmp`, no non-local jumps that could be used as covert channels

## Usability Without Compromise

Jo solves the usability problem through _context parameters_ — capabilities that flow implicitly through call chains:

```jo
param logger: Logger

def processOrder(id: String): Order =
  logger.info("Processing: " + id)  // logger available implicitly
  fetchOrder(id)

def handleRequest(req: Request): Response =
  processOrder(req.orderId)  // logger flows through automatically
```

Context parameters provide the ergonomics of global variables without sacrificing security principles. The compiler infers capability requirements, so developers rarely write explicit `receives` clauses.

Unlike ambient authorities, capabilities are always statically tracked and controllable — every function declares what it needs, and callers can further restrict what is permitted:

```jo
// Explicit specification — declare exactly what a function may use
def analyze(data: String): Report receives logger, db =
  logger.info("Analyzing...")
  db.query(data)

// Explicit control — restrict what capabilities are permitted at call site
def main() =
  allow logger in analyze(input)        // ERROR: db not allowed
  allow logger, db in analyze(input)    // OK: all required capabilities permitted
  allow none in analyze(input)          // ERROR: logger and db not allowed
```

The `receives` clause specifies required capabilities; the `allow` clause at the call site enforces that only permitted capabilities are used. Both are verified at compile time. This makes capability flow explicit and auditable while keeping the common case concise.

More details of the capability model can be found in the paper [_A mathematical model of contextual capabilities_](https://github.com/typescope/contextual-capability).

## Security Context

Jo's language features enable safe representation and encapsulation of security contexts.

**Representation** — The security context (current user) is captured in a capability object:

```jo
// Framework library (trusted code)
class UserScopedOrders(userId: Int, db: Database)
  def query(lastDays: Int): List[Order] =
    db.query("SELECT * FROM orders WHERE user_id = ? AND date > CURRENT_DATE - ?", userId, lastDays)

  view OrdersApi
end
```

The `userId` is captured in the object — when untrusted code calls `ordersApi.query(30)`, it automatically queries only that user's orders. The security context is embedded in the capability itself.

**Encapsulation** — Untrusted code sees only the abstract interface:

```jo
// API library — this is all the AI code can see
interface OrdersApi
  def query(lastDays: Int): List[Order]
end

param ordersApi: OrdersApi

// AI-generated code
def aiAnalyze(): Unit receives ordersApi, IO.stdout =
  val data = ordersApi.query(30)  // cannot access userId or db
  summarize(data)
```

The type system guarantees that untrusted code cannot downcast `OrdersApi` to `UserScopedOrders`, inspect the object, or access the underlying `userId` or `db` fields.

## Attenuation of Authorities

Lambdas enable the creation of attenuated capabilities from more powerful authorities:

```jo
// Framework library (trusted code)
def frameworkMain() =
  val db = connect("orders.db")           // full database access
  val userId = currentUser()              // security context

  // Attenuate: full DB → user-scoped, read-only
  val restricted = new UserScopedOrders(userId, db)

  val output: mutable.List[String] = []
  val buffer = (s: String) => output += s
  allow none in
    with ordersApi = restricted, IO.stdout = buffer in aiMain()
```

The attenuation chain:

1. `db` — full database access (all tables, all users, read/write)
2. `ordersApi` — the only view exposed to untrusted code (read-only access of user's rows)

The `allow none` clause proves at compile time that `aiMain()` uses no capabilities beyond `ordersApi` and `IO.stdout`. Any attempt to access filesystem, network, or other users' data is a compile-time error.

**Views as attenuation.** Attenuation also works through Jo's view mechanism. A class that satisfies multiple interfaces can be passed under just one of them, restricting the recipient to that interface's methods:

```jo
class AdminService
  def readOrders(userId: Int): List[Order] = ...
  def deleteOrder(id: Int): Unit = ...
  view OrdersApi    // read-only view
  view AdminApi     // full admin access
end

val admin = new AdminService()

// Passing as OrdersApi attenuates to read-only — deleteOrder is not accessible
runAgent(admin as OrdersApi)
```

## See Also

- [Two-World Architecture](two-worlds.md) — How the confined/trusted separation enforces these guarantees
- [Security Examples](examples/index.md) — Hands-on examples
