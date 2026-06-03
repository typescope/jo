# Jo's Solution

Jo addresses the three challenges identified in [The AI Security Problem](security-problem.md): removing ambient authorities without sacrificing usability, representing and encapsulating security context, and attenuating authorities across trust boundaries.

## Overview: Confining AI-Generated Code

The following example demonstrates Jo's complete security architecture:

```jo
//------------------ Interface library ---------------------------
class Order(...)

interface OrdersApi                        // (1)
  def query(lastDays: Int): List[Order]
end

param ordersApi: OrdersApi

//------------------ Framework library -----------------------
defer def aiMain(): Unit receives ordersApi, IO.stdout  // (2)

class UserScopedOrders(userId: Int, db: Database)    // (3)
  def query(lastDays: Int): List[Order] =
    db.query("SELECT * FROM orders WHERE user_id = ? AND date > CURRENT_DATE - ?", userId, lastDays)

  view OrdersApi
end

def frameworkMain() =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = new UserScopedOrders(userId, db)  // (4)

  // Capture AI code output
  val output: mutable.List[String] = []
  val buffer = (s: String) => output += s

  allow none in // (5)
    with ordersApi = restricted, IO.stdout = buffer in aiMain()

  // ...

//------------------ AI-generated code ---------------------
def aiMain(): Unit receives ordersApi, IO.stdout =   // (6)
  val data = ordersApi.query(30)
  summarize(data)
```

1. The only capability interface available to AI code. The interface library is compiled without FFI support.
2. The signature that AI-generated code must conform to.
3. Trusted implementation captures `userId` — untrusted code cannot access it.
4. Capability attenuated: full DB access → user-scoped, read-only.
5. `allow none` proves at compile time: AI code cannot access network, filesystem, or other data.
6. AI-generated code is verified against the interface library, then linked with the framework library.

The AI code cannot access the network, filesystem, or other users' data — the compiler enforces this statically. After type checking, no runtime sandboxing is needed.

## Eliminating Ambient Authorities

Jo removes all ambient authorities from the language:

- **No global variables** — all state is explicitly passed
- **No FFI in untrusted code** — foreign function interface is restricted to trusted code
- **No reflection** — runtime introspection cannot bypass the type system

In Jo, the Python attack from the problem statement is impossible:

```jo
// This is ALL the untrusted code can do — no os, no network, no filesystem
def summarize(content: String): String receives none =
  // Pure computation only
  extractKeyPoints(content)
```

The `receives none` clause is compiler-verified: the function cannot access any capability.

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

**Unlike ambient authorities, capabilities are always statically tracked and controllable:**

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

## Security Context

Jo addresses both dimensions of the security context problem identified earlier.

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

The framework creates attenuated capabilities from more powerful authorities:

```jo
// Framework library (trusted code)
def frameworkMain() =
  val db = connect("orders.db")           // full database access
  val userId = currentUser()              // security context

  // Attenuate: full DB → user-scoped, read-only
  val restricted = new UserScopedOrders(userId, db)

  val buffer = (s: String) => output += s
  allow none in
    with ordersApi = restricted, IO.stdout = buffer in aiMain()
```

The attenuation chain:

1. `db` — full database access (all tables, all users, read/write)
2. `ordersApi` — the only view exposed to untrusted code (read-only access of user's rows)

The `allow none` clause proves at compile time that `aiMain()` uses no capabilities beyond `ordersApi` and `IO.stdout`. Any attempt to access filesystem, network, or other users' data is a compile-time error.

## Two-World Architecture

Jo enforces a strict separation between **confined code** (no FFI, type-checked against confined libraries only) and **trusted code** (FFI allowed, provides capabilities). This separation is what makes the compile-time guarantees possible.

See [Two-World Architecture](two-worlds.md) for the full explanation, build steps, and diagrams.
## Assurance

The security guarantees depend on Jo's type system. We intentionally keep the type system nominal and simple — avoiding complex features like structural subtyping and complex type inference that have been sources of soundness bugs in other languages. The type system is intentionally kept small enough to reason about formally — complex features like structural subtyping and deep type inference are excluded precisely because they have been sources of soundness bugs in other systems.

## Threat Mitigation

Jo provides the following compile-time guarantees for untrusted code:

1. **No unauthorized I/O** — All side effects require explicit capabilities
2. **No capability amplification** — Attenuated capabilities cannot recover broader access
3. **No covert channels via language features** — No reflection, no ambient state

These guarantees hold without runtime sandboxing. The type system enforces the security boundary.

Returning to the threat model:

| Attacker Goal | Jo's Mitigation |
|---------------|-----------------|
| Access other users' data | Capabilities are user-scoped |
| Escalate privileges | `allow none` proves no undeclared capabilities used |
| Exfiltrate data | No network access without explicit capability |

## What's Next?

- [Security Demos](../security/examples/index.md) — Hands-on examples demonstrating Jo's security features
