# Deferred Function Definitions

Deferred functions are declared without implementation and linked at compile time, enabling modular compilation and late binding.

## Syntax

```jo
defer def functionName(params): ReturnType
```

## Basic Deferred Functions

Declare a function signature without implementation:

```jo
// Deferred function declaration
defer def authenticate(token: String): User

// Usage in other functions
def handleRequest(request: Request): Response =
  val user = authenticate(request.token)
  // ... handle request with authenticated user
```

## Linking at Compile Time

Deferred functions are linked using the `-link` compiler option:

```bash
# Link MyApp.authenticate to OAuth.verify implementation
bin/jo build -link MyApp.authenticate=OAuth.verify app.jo -o app
```

## Use Cases

### Plugin Architecture

```jo
// Core application
defer def processPayment(amount: Float): Result

def checkout(cart: ShoppingCart): Result =
  val total = cart.calculateTotal()
  processPayment(total)

// Link to specific payment provider
// -link MyApp.processPayment=StripeProvider.process
// -link MyApp.processPayment=PayPalProvider.process
```

### Testing

```jo
// Production code
defer def sendEmail(to: String, subject: String, body: String): Unit

def notifyUser(user: User, message: String): Unit =
  sendEmail(user.email, "Notification", message)

// Test - link to mock implementation
// -link App.sendEmail=TestUtils.mockEmail
```

### Platform Abstraction

```jo
// Platform-independent code
defer def getFileSystem(): FileSystem
defer def getNetwork(): Network

def syncData(): Unit =
  val fs = getFileSystem()
  val net = getNetwork()
  // ... sync logic

// Link to platform-specific implementations
// Linux: -link App.getFileSystem=LinuxFS.create
// Windows: -link App.getFileSystem=WindowsFS.create
```

## Multiple Deferred Functions

```jo
defer def authenticate(token: String): User
defer def authorize(user: User, resource: Resource): Bool
defer def log(message: String): Unit

def handleRequest(request: Request): Response =
  val user = authenticate(request.token)
  if authorize(user, request.resource) then
    log("Access granted: " + user.name)
    processRequest(request)
  else
    log("Access denied: " + user.name)
    Forbidden("Access denied")
```

## With Effects

Deferred functions can declare effects:

```jo
defer def readConfig(): Config receives open
defer def saveState(state: State): Unit receives open, IO

def initialize(): Unit receives open, IO =
  val config = readConfig()
  val state = createInitialState(config)
  saveState(state)
```

## Generic Deferred Functions

```jo
defer def serialize[T](value: T): String
defer def deserialize[T](json: String): T

def saveToFile[T](value: T, path: String): Unit receives open =
  val json = serialize(value)
  File.write(path, json)

def loadFromFile[T](path: String): T receives open =
  val json = File.read(path)
  deserialize[T](json)
```

## Link Syntax

The `-link` option uses this syntax:

```bash
-link <deferred_function>=<implementation_function>
```

Examples:

```bash
# Simple linking
-link App.authenticate=OAuth.verify

# Multiple links
-link App.authenticate=OAuth.verify -link App.log=ConsoleLogger.log

# Generic function linking
-link App.serialize=JsonSerializer.serialize
```

## Examples

### Dependency Injection

```jo
// Interface
defer def getDatabase(): Database
defer def getCache(): Cache

// Application
def loadUser(id: UserId): User =
  val db = getDatabase()
  val cache = getCache()

  match cache.get(id)
  case Some(user) => user
  case None =>
    val user = db.findUser(id)
    cache.put(id, user)
    user

// Production linking
// -link App.getDatabase=PostgresDB.instance
// -link App.getCache=RedisCache.instance

// Test linking
// -link App.getDatabase=InMemoryDB.instance
// -link App.getCache=NoOpCache.instance
```

### Configuration

```jo
defer def getEnvironment(): Environment
defer def getLogLevel(): LogLevel

def initializeLogging(): Unit =
  val env = getEnvironment()
  val level = getLogLevel()

  Logger.configure(env, level)

// Development
// -link App.getEnvironment=Dev.environment
// -link App.getLogLevel=Dev.debugLevel

// Production
// -link App.getEnvironment=Prod.environment
// -link App.getLogLevel=Prod.infoLevel
```

### Strategy Pattern

```jo
defer def sortStrategy[T](items: List[T]): List[T]

def processData[T](data: List[T]): Result =
  val sorted = sortStrategy(data)
  analyze(sorted)

// Link to different strategies
// -link App.sortStrategy=QuickSort.sort   # Fast, general
// -link App.sortStrategy=MergeSort.sort   # Stable
// -link App.sortStrategy=HeapSort.sort    # In-place
```

## Best Practices

### Clear Contracts

Provide clear function signatures:

```jo
// ✓ Good - clear contract
defer def authenticate(token: String): User
defer def authorize(user: User, permission: Permission): Bool

// ⚠ Too vague
defer def check(data: Any): Any
```

### Document Requirements

```jo
// Document what implementations must provide
defer def processPayment(amount: Float): Result
  // Implementation must:
  // - Validate amount > 0
  // - Return Ok(transactionId) on success
  // - Return Err(errorMessage) on failure
```

### Type Safety

Use strong types instead of generic parameters when possible:

```jo
// ✓ Good - specific types
defer def parseConfig(json: String): Config

// ⚠ Less safe - generic
defer def parse[T](data: String): T
```

## Limitations

- Deferred functions must be linked at compile time
- Cannot be dynamically dispatched at runtime
- All uses must link to the same implementation in a build

## See Also

- [Function Definitions](function-definitions.md) - Regular function definitions
- For detailed design, see [Deferred Functions](../../design/deferred-functions.md)
