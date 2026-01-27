# Jo's Solution

Jo is a statically typed language designed to solve the authority confinement problem for LLM-generated code. This document explains how Jo addresses each challenge identified in [The AI Security Problem](security-problem.md).

## Overview: Confining AI-Generated Code

The following example demonstrates Jo's complete security architecture:

```jo
//------------------ API library ---------------------------
class Order(...)

interface OrdersApi                        // (1)
  def query(lastDays: Int): List[Order]
end

param orders: OrdersApi

//------------------ Harness library -----------------------
defer def aiMain(): Unit receives orders, IO.stdout  // (2)

class UserScopedOrders(userId: Int, db: Database)    // (3)
  def query(lastDays: Int): List[Order] =
    db.query("SELECT * FROM orders WHERE user_id = ? AND date > CURRENT_DATE - ?", userId, lastDays)
    // ...

  view OrdersApi
end

def harnessMain() =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = new UserScopedOrders(userId, db)  // (4)

  // Capture AI code output
  val output: ArrayBuffer[String] = []
  val buffer = (s: String) => output += s

  aiMain()
    with orders = restricted, IO.stdout = buffer
    allow none // (5)

  // ...

//------------------ AI-generated code ---------------------
def aiAnalyze(): Unit receives orders, IO.stdout =   // (6)
  val data = orders.query(30)
  summarize(data)
```

1. The only capability interface available to AI code. The API library is compiled without FFI support.
2. The signature that AI-generated code must conform to.
3. Trusted implementation captures `userId` — untrusted code cannot access it.
4. Capability attenuated: full DB access → user-scoped, read-only.
5. `allow none` proves at compile time: AI code cannot access network, filesystem, or other data.
6. AI-generated code is verified against the API library, then linked with the harness.

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
  analyze(input) allow logger        // ERROR: db not allowed
  analyze(input) allow logger, db    // OK: all required capabilities permitted
  analyze(input) allow none          // ERROR: logger and db not allowed
```

The `receives` clause specifies required capabilities; the `allow` clause at the call site enforces that only permitted capabilities are used. Both are verified at compile time. This makes capability flow explicit and auditable while keeping the common case concise.

## Security Context

Jo addresses both dimensions of the security context problem identified earlier.

**Representation** — The security context (current user) is captured in a capability object:

```jo
// Harness library (trusted code)
class UserScopedOrders(userId: Int, db: Database)
  def query(lastDays: Int): List[Order] =
    db.query("SELECT * FROM orders WHERE user_id = ? AND date > CURRENT_DATE - ?", userId, lastDays)
    // ...

  view OrdersApi
end
```

The `userId` is captured in the object — when untrusted code calls `orders.query(30)`, it automatically queries only that user's orders. The security context is embedded in the capability itself.

**Encapsulation** — Untrusted code sees only the abstract interface:

```jo
// API library — this is all the AI code can see
interface OrdersApi
  def query(lastDays: Int): List[Order]
end

param orders: OrdersApi

// AI-generated code
def aiAnalyze(): Unit receives orders, IO.stdout =
  val data = orders.query(30)  // cannot access userId or db
  summarize(data)
```

The sound type system guarantees that untrusted code cannot downcast `OrdersApi` to `UserScopedOrders`, inspect the object, or access the underlying `userId` or `db` fields.

## Attenuation of Authorities

The harness creates attenuated capabilities from broader ones:

```jo
// Harness library (trusted code)
def harnessMain() =
  val db = connect("orders.db")           // full database access
  val userId = currentUser()              // security context

  // Attenuate: full DB → user-scoped, read-only, time-limited
  val restricted = new UserScopedOrders(userId, db)

  aiMain() with orders = restricted, IO.stdout = buffer allow none
```

The attenuation chain:

1. `db` — full database access (all tables, all users, read/write)
2. `orders` — the only view exposed to untrusted code (read-only access of user's rows)

The `allow none` clause proves at compile time that `aiMain()` uses no capabilities beyond `orders` and `IO.stdout`. Any attempt to access filesystem, network, or other users' data is a compile-time error.

## Two-World Architecture

Jo enforces a strict separation between trusted and untrusted code through separate compilation:

| Property | Pure World (Untrusted) | Runtime World (Trusted) |
|----------|------------------------|-------------------------|
| FFI access | Prohibited | Permitted |
| System calls | Prohibited | Permitted |
| Security review | Minimal | Required |

**Pure World** — Jo standard library, API definitions and untrusted code:

```jo
// Api.jo — compiled without FFI support
interface OrdersApi
  def query(days: Int): List[Order]
end

defer def aiMain(): Unit receives OrdersApi, IO.stdout
```

The Jo standard library is untrusted and has no FFI support: it lives in the
pure world.
A pure world library may only depend on pure world libraries for type checking.

**Runtime World** — Platform runtime libraries and trusted API implementation:

```jo
// Harness.jo — compiled with FFI support
def platformMain() =
  val db = js "new Database(process.env.DB_PATH)"
  val userId = js "parseInt(process.argv[2])"

  val api = new UserScopedOrdersApi(userId, db)
  val output = new StringBuilder

  aiMain() with
    OrdersApi = api,
    IO.stdout = (s: String) => output.append(s)
  allow none

  // output now contains captured AI output for review
```

Jo provides root runtime libraries for each compilation target (Ruby, Python, JS).
The root runtime libraries expose FFI capabilities which enable users to develop
trusted API implementation.

The `defer def` declares a function signature that untrusted code must implement.
At link time, the trusted harness provides capabilities to the untrusted implementation.

!!!warning "Untrusted code only depend on code in the pure world"

    Untrusted code may only live in the pure world and they cannot directly or
    indirectly depend on runtime libraries for type checking.

    The linking mechanism provides the glue between untrusted code and trusted
    code through dependency inversion: the trusted code define a stub with
    explicit contract for untrusted code.


## Threat Mitigation

Jo provides the following compile-time guarantees for untrusted code:

1. **No unauthorized I/O** — All side effects require explicit capabilities
3. **No capability amplification** — Attenuated capabilities cannot recover broader access
4. **No covert channels via language features** — No reflection, no ambient state

These guarantees hold without runtime sandboxing. The type system is the security guard.

Returning to the threat model:

| Attacker Goal | Jo's Mitigation |
|---------------|-----------------|
| Access other users' data | Capabilities are user-scoped |
| Escalate privileges | `allow none` proves no undeclared capabilities used |
| Exfiltrate data | No network access without explicit capability |
