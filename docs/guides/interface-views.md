# Classes and Views

Every non-trivial class eventually needs to satisfy interface contracts. Traditional
OOP gives you two tools, but makes one of them painful:

- **Inheritance** — declare it, get automatic subtype relationships for free
- **Composition** — write a forwarding method for every single delegated method

This asymmetry pushes code toward inheritance even when composition is the better
design choice. Jo eliminates it with a unified mechanism: **views**.

## The Two Forms

There are two forms of views:

- direct views
- delegate views

Both create a subtyping relationship `C <: I`. The choice is about where the
implementation lives.

### Direct View

Use `view I` when the class itself provides the behavior. The compiler verifies all
abstract methods are present with compatible signatures.

```jo
class RangeIterator(range: Range)
  var current: Int = range.start
  def hasNext(): Bool = current < range.ends
  def next(): Int =
    val v = current
    current = current + 1
    v
  view Iterator[Int]
end
```

### Delegate View

The traditional alternative to inheritance for composition looks like this in Java:

```java
class Service implements Logger {
    private final Logger logger;
    Service(Logger logger) { this.logger = logger; }

    // Forwarding boilerplate — must be repeated for every method
    public void log(String msg)   { logger.log(msg); }
    public void error(String msg) { logger.error(msg); }
}
```

In Jo:

```jo
class Service(logger: Logger)
  view Logger = logger   // compiler synthesizes the forwarders
end
```

The compiler generates a forwarding method for each **abstract** method of the
interface. The delegate must be a stable reference (an immutable field or chain of
immutable field selections) that conforms to the interface.

## Combining Views

A class can hold multiple views — direct and delegate — simultaneously:

```jo
class Task(name: String, logger: ConsoleLogger)
  def run(): String = "[ran " + name + "]"

  view Runnable          // Task implements Runnable itself
  view Logger = logger   // Logger forwarded to the logger field
end

val t = new Task("Upload", new ConsoleLogger("[TASK]"))
execute(t)    // uses Task.run() via Runnable
logTo(t)      // uses forwarded Logger
```

## Authority Attenuation

Views are also a mechanism for **authority attenuation**: passing an object to code that
should only access a subset of its capabilities. Because `C <: I`, you can upcast a
value to any interface it views and hand that narrowed reference to code that receives
only `I` — the recipient cannot reach capabilities not declared in `I`.

```jo
interface Reader
  def read(path: String): String
end

interface Writer
  def write(path: String, data: String): Unit
end

class LocalFS
  def read(path: String): String = ...
  def write(path: String, data: String): Unit = ...
  view Reader
  view Writer
end

// This function receives only Reader — write() is not accessible inside it
def generateReport(src: Reader): String =
  src.read("data.csv")

val fs = new LocalFS()
generateReport(fs as Reader)   // static type is Reader; write() authority is attenuated away
```

This is the same principle used by the capability system (`param`/`allow`): give each
piece of code the narrowest interface it needs, and the type system enforces the
boundary statically.

## Constraints

**No duplicate views** — a class cannot declare two views of the same interface; it
would be ambiguous which provides the implementation.

**No name conflicts** — a class method cannot share a name with a synthesized
forwarder, and two delegate views cannot both forward a method of the same name.

## See Also

- [Class Definitions](../language/definitions/class-definitions.md) — conformance rules and technical details
- [Interface Definitions](../language/definitions/interface-definitions.md) — interface method semantics
