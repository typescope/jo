# Type-Safe Dependency Injection

Jo provides first-class facilities for dependency injection without frameworks, XML configuration, or runtime reflection. Dependencies are expressed in the type system, verified at compile time, and carry zero runtime overhead.

Two complementary mechanisms cover the full spectrum of injection needs:

- **Context parameters** — inject service dependencies into call chains
- **Deferred functions and linking** — invert structural dependencies between packages

## Context Parameters

A `param` declaration introduces a named, typed dependency. Any function that uses it — directly or transitively — automatically requires it to be bound. Bindings are supplied at the call site with `with`:

```jo
interface MovieFinder
  def findAll(): List[Movie]
end

param finder: MovieFinder

def moviesDirectedBy(director: String): List[Movie] =
  finder.findAll().filter(m => m.director == director)
```

Inject a real or test implementation at the call site:

```jo
class ColumbiaArchive
  def findAll(): List[Movie] = loadFromDisk("archive.db")
  view MovieFinder
end

class FakeFinder
  def findAll(): List[Movie] = [Movie("A", "Hitchcock", 1960)]
  view MovieFinder
end

def main =
  // Production
  moviesDirectedBy("Hitchcock") with finder = new ColumbiaArchive

  // Test — swap without touching any other code
  moviesDirectedBy("Hitchcock") with finder = new FakeFinder
```

No factory, no container, no annotation. The compiler tracks every context parameter through the full call chain and reports a precise error with a dependency trace if a binding is missing:

```
---------- Error at app.jo:12:3 ---------------
|   moviesDirectedBy("Hitchcock")
|   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
|   Context parameter not provided: finder

The following is the trace that leads to the problem:
├──   moviesDirectedBy("Hitchcock")	[ app.jo:12:3 ]
│     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
└── def moviesDirectedBy(...) = finder.findAll()...	[ app.jo:4:3 ]
                                 ^^^^^^
```

### Propagation and Shadowing

Bindings propagate automatically through nested calls. Inner bindings shadow outer ones for the duration of their scope:

```jo
param logger: Logger

def save(record: Record): Unit =
  logger.info("Saving \{record.id}")
  store(record)

def process(records: List[Record]): Unit =
  for r in records do save r    // Uses outer logger

def main =
  val prod   = new FileLogger("app.log")
  val silent = new NullLogger

  process(records) with logger = prod     // All saves logged
  process(records) with logger = silent   // All saves suppressed
```

### Safe Circular Dependencies

Circular dependencies are a notorious footgun in traditional DI frameworks. Consider two services that legitimately need each other — an `AuthService` that logs every access check, and an `AuditService` that attributes each log entry to the current user:

```java
// Java / Spring — circular injection
@Service
class AuthService {
    @Autowired AuditService audit;   // Spring injects a proxy...
    public boolean check(String user, String action) {
        audit.record(user, action);  // ...which may be null at this point
        return permitted(user, action);
    }
}

@Service
class AuditService {
    @Autowired AuthService auth;
    public void record(String user, String action) {
        String caller = auth.currentUser();  // runtime NullPointerException
        persist(caller, user, action);
    }
}
```

Frameworks resolve this with lazy proxies: a placeholder object is injected during construction and filled in later. The proxy works most of the time — but if either service is called during its own construction, the proxy is still null and the program crashes at runtime. The compiler cannot catch this.

**Jo makes circular dependencies safe by construction.** Context parameters are not injected into objects at construction time — they are bound at the *call site*, after both objects are fully initialized. There is no proxy, no deferred wiring, and no window during which a dependency can be null:

```jo
interface AuthService
  def check(user: String, action: String): Bool receives auditService
  def currentUser(): String  // simple session lookup — does not call auditService
end

interface AuditService
  def record(user: String, action: String): Unit receives authService
end

param authService: AuthService
param auditService: AuditService

class AuthImpl
  def check(user: String, action: String): Bool =
    auditService.record(user, action)  // log every access check
    permitted(user, action)

  def currentUser(): String = session.user  // plain lookup, no audit call
  view AuthService
end

class AuditImpl
  def record(user: String, action: String): Unit =
    val caller = authService.currentUser()  // who triggered this? — no cycle
    persist(caller, user, action)
  view AuditService
end

def main =
  val auth  = new AuthImpl   // (1)!
  val audit = new AuditImpl  // (2)!
  auth.check("alice", "login") with authService = auth, auditService = audit  // (3)!
```

1. Both objects are constructed first — no proxies, no partial initialization
2. Construction order does not matter; neither object references the other yet
3. Bindings are supplied at the call site, after both services are fully ready

The type system enforces this discipline statically. The `receives` clause on each interface method makes the circular dependency explicit and visible. The compiler verifies that every context parameter is bound before any call is made — there is no runtime equivalent of a null or uninitialized proxy.

## Deferred Functions and Linking

Context parameters inject service values into a running program. Deferred functions go further: they let a library define the *shape* of a dependency without importing anything, keeping the library itself free of transitive dependencies.

Declare an extension point with `defer`:

```jo
// reportLib/report.jo  — depth-0 library, no imports
namespace Report

interface Renderer
  def render(doc: String): String
end

defer def newRenderer(): Renderer

def generate(title: String, body: String): String =
  newRenderer().render("# " + title + "\n\n" + body)
```

Supply the implementation at compile time with `--link`:

```bash
# Production build — use the HTML renderer
bin/jo compile app.jo \
  --link Report.newRenderer=HtmlLib.newHtmlRenderer \
  -o app

# Test build — use an in-memory stub
bin/jo compile app.jo \
  --link Report.newRenderer=TestLib.newStubRenderer \
  -o app-test
```

Because `report` carries no imports, it remains a depth-0 library. The concrete renderer package is a concern of the application, not the library. The dependency graph is inverted.

Linking is type-checked: if the linked function's signature does not match the deferred declaration, the compiler reports an error and stops compilation.

### Framework-Controlled Entry Points

Deferred functions also let a framework own the application lifecycle while remaining decoupled from the application code:

```jo
// framework.jo
namespace Framework

defer def init(): Unit
defer def handle(request: Request): Response

def run(): Unit =
  init()
  serve(port = 8080, handler = r => handle(r))
```

```bash
bin/jo compile framework.jo app.jo \
  --link jo.main=Framework.run \
  --link Framework.init=App.init \
  --link Framework.handle=App.handle \
  -o server
```

The framework defines the contract; the application fills it in; the linker connects them. No base classes, no annotations, no runtime container.

## Comparison with Common Alternatives

| | Jo DI | Constructor Injection | Runtime Container |
|---|---|---|---|
| Type-safe | Compile-time | Compile-time | Runtime |
| Missing dependency | Compile error | Compile error | Runtime error |
| Circular dependencies | Safe | Risky | Risky |
| Swap for testing | `with` at call site | Rebuild object graph | Reconfigure container |
| Runtime overhead | Zero | Zero | Reflection |
| Framework required | No | No | Yes |

## See Also

- [Context Parameters](../language/concepts/context-parameters.md) - Full reference for `param`, `with`, `allow`, and `receives`
- [Deferred Functions](../language/definitions/deferred-functions.md) - Full reference for `defer` and `-link`
- [Dependency Depth](../usage/concepts/dependency-depth.md) - How deferred functions keep libraries shallow
- [Capability-Oriented Programming](capabilities.md) - Using the same mechanisms for security and confinement
