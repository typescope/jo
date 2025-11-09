# Jo's Solution

Jo addresses the three fundamental challenges of object-capability languages through its type system design and two-world architecture.

## Static Capability Control

Jo provides static verification of capability access through typed context parameters:

```jo
param database: Database
param logger: Logger

def processOrder(orderId: String): Result[Order] receives database, logger =
  logger.info("Processing order: " + orderId)
  database.findOrder(orderId)
```

The `receives` clause statically declares required capabilities. The type system ensures:

- Functions cannot access undeclared capabilities
- Capability requirements are verified at compile time
- Security properties are enforced without runtime overhead

The `receives` clause can be inferred automatically --- no syntatic overhead.

## Global Variable Solution

Jo eliminates ambient authority through context parameters while maintaining usability:

```jo
// Global-like access without global variables
param config: Config
param database: Database

def createUser(name: String): User receives config, database =
  val timeout = config.databaseTimeout
  database.insert("users", name, timeout)

// Capabilities flow automatically through the call chain
// receives clause can be inferred - no syntactic overhead
def handleRequest(req: Request): Response =
  val user = createUser(req.userName)  // capabilities passed implicitly
  #Success(user)
```

Context parameters provide the convenience of global access while maintaining explicit capability control and enabling capability confinement for security. The `receives` clause can be inferred by the compiler, eliminating syntactic overhead while preserving static verification.

## Two-World Architecture

Jo distinguishes libraries into two worlds:

**Pure World** - Untrusted user code and libraries that do not depend on runtime libraries:
```jo
// UserApp.jo - cannot perform I/O by itself
def analyzeData(): Unit receives db, logger =
  val docs = db.queryMyDocuments()  // uses capability interface
  logger.info("Found " + intToStr(docs.size) + " documents")
  docs.foreach(doc => println(doc.title))
```

**Runtime World** - Trusted platform libraries that provide capabilities:
```jo
// PlatformRuntime.jo - trusted implementation
def platformMain: Unit receives stdout =
  val userId = js "parseInt(process.argv[2])"  // direct system access
  val dbHandle = js "new DatabaseSync(dbPath)"

  UserApp.analyzeData with
    db = {
      def queryMyDocuments(): List[Document] =
        // userId captured in closure - user code cannot access it
        execQuery("SELECT * FROM documents WHERE owner_id = ?", userId)
    },
    logger = { def info(msg: String): Unit = println("[INFO] " + msg) }
```

**Compilation Constraints:**

- Pure world code **cannot import** runtime world libraries during compilation
- Runtime world libraries **can import** pure world libraries
- The two worlds are **linked** at build time through deferred functions and context parameters

**Benefits:**

- **Clear trust boundary** - Only runtime world can perform actual I/O or system calls
- **Untrusted pure world** - User code cannot do anything harmful by itself
- **Simple interoperability** - Pure world accesses capabilities through typed interfaces only
- **Third-party extensibility** - Platforms can add new runtime libraries without language changes
- **Security auditing** - Only runtime world implementations need security review

## How It Works

Jo's solution addresses each challenge:

1. **Static control** - Type system enforces capability requirements at compile time
2. **Checked ambient authority** - Context parameters eliminate global variables and simplify reasoning about security
3. **Secure interoperability** - Two-world architecture provides clear boundaries and extensibility

This design enables fine-grained capability control suitable for secure AI code generation while maintaining the expressiveness needed for practical programming.
