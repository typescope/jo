<h1 align="center"> 🛡 The Jo Programming Language️</h1>
<h3 align="center">For the joy of secure programming</h3>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#demos">Demos</a> •
  <a href="#examples">Examples</a> •
  <a href="#usage">Usage</a>
</p>

---

Jo is a secure programming language designed for securing LLM generated code.

- **API confinement** - LLM generated code is confined to custom-defined application-level APIs
- **Fine-grained control** - Precise control of authorization, e.g. only access certain rows of a data table
- **Easy security auditing** - Statically checked authorities and clear security boundaries

<a id="features"></a>
## Key Features ✨

- **Extensible runtime** - Extend and customize the language runtime with a Jo library
- **No global variables** - Safe and easy to compose and for reuse
- **Context parameters** - Contextual abstraction, optional parameters, and implicit resolution
- **Effect system** - Fine-grained effect control with parametric effects
- **Algebraic data types** - Extensible ADTs with pattern matching
- **Pattern-oriented programming** - First-class patterns and higher-order patterns
- **Natural syntax**  - Prefix, infix, and postfix operators; two call styles `f(x)` and `f x`; indentation-based
- **Multiple backends**  - Interpreter, JavaScript, native x86 Linux, and more are coming (Python, Java, Ruby)

<a id="get-started"></a>
## Getting Started 🚀

```bash
# Run a program (interpreter)
bin/jo tests/pos/hello.jo

# Build x86 linux native executable
bin/jo build tests/pos/hello.jo -o hello
./hello

# Build JavaScript
bin/jo build -js tests/pos/hello.jo -o hello.js
node hello.js
```

<a id="demos"></a>

## Demos 🎈

Real-world applications demonstrating Jo's capability-based security model:

### System Monitor

**Location**: `demos/process-monitor/`

A system monitoring application that extends Jo's JavaScript runtime with real system-level capabilities.

- **Runtime extension** using the `-runtime` flag and `js` intrinsic
- **Context parameters** for capability provision via typed objects
- **Object-oriented API design** with capability grouping (Process, System, Logger)
- **Three-stage compilation** (API → Runtime → User code)
- **Security confinement** - user code analyzes system processes without direct Node.js access
- **Section-based organization** - Implementation functions organized in three sections (`section Process`, `section System`, `section Logger`) matching the three context parameter types

It shows how platforms can expose controlled system APIs (process listing, memory usage, system info) to untrusted user code while preventing arbitrary command execution.

### Data Table Access Control

**Location**: `demos/data-table/`

A database application demonstrating **row-level security** with SQLite, where different users can only access their own database rows.

- **Command-line arguments** for userId and database path (hidden from user code)
- **User-aware runtime** - runtime captures userId and provides filtered database access
- **Automatic query filtering** - all SQL queries filtered by `WHERE owner_id = ?`
- **Type-safe database interface** - user code cannot write raw SQL
- **Compiler-enforced security** - impossible to bypass filtering, even with malicious code
- **Section-based organization** - Implementation functions organized in `section Impl` for clarity

It shows how a user-aware runtime can enforce row-level access control and automatically filtering all database queries, making it impossible for user code to access data belonging to other users.

### Data Table with Query DSL

**Location**: `demos/data-table-query/`

An extension of the data-table demo that adds **flexible custom filter conditions** while maintaining row-level security.

- **Query DSL** - Type-safe expression builder using infix operators (`like`, `>`, `&&`, `||`)
- **Atomic operators** - Composable building blocks (Eq, Like, Gt, Lt, And, Or)
- **Schema-driven API** - Typed column references via `getSchema()`
- **SQL-level filtering** - Efficient for large datasets (no in-memory filtering)
- **Pattern matching** - Runtime translates query AST to parameterized SQL
- **Automatic security** - User conditions always ANDed with `WHERE owner_id = ?`
- **Section-based organization** - Implementation functions organized in `section Impl` with context parameter `param dbHandle: Any`

It shows how to provide expressive query capabilities while maintaining security: users can build complex filters like `(table.title like "%Report%") && (table.createdAt > str("2024-01-15"))`, but the runtime always enforces row-level access control.

<a id="examples"></a>

## Examples 💡

### Hello world

```
def main = println "Hello world!"
```

### [Lambda Calculus](https://en.wikipedia.org/wiki/Lambda_calculus)

```
data Expr =
    Abs(x: String, body: Expr)
  | Var(x: String)
  | App(lhs: Expr, arg: Expr)

data Env = Empty | Cons(k: String, v: Value, outer: Env)

data Value(lambda: Abs, env: Env)

param env: Env = Empty // environment for variables

def show(expr: Expr): String =
  match expr
    case Var x => x

    case Abs x t => "(\\" + x + "." + (show t) + ")"

    case App abs arg =>
      (show abs) + " " + (show arg)

def find(x: String): Option[Value] =
  match env
    case Empty => None

    case Cons k v outer =>
      if x == k then Some(v)
      else find x with env = outer

def eval(expr: Expr): Value =
  match expr
    case Var x =>
      match find x
        case None => abort ("Unfound variable: " + x)
        case Some v => v
      end

    case abs: Abs =>
      Value abs env

    case App abs arg =>
      val closure = eval abs
      val argValue = eval arg
      val x = closure.lambda.x
      val body = closure.lambda.body
      val env2: Env = Cons(x, argValue, closure.env)
      eval body with env = env2

def main =
  val id: Expr = Abs("x", Var "x")
  val code: Expr = App(id, id)
  println
    show (eval code).lambda
```

### More Examples

Explore complete examples showcasing Jo's features:

- **[Expression Problem](tests/pos/expression-problem.jo)** - Extensible ADTs demonstrating Jo's solution to the expression problem
- **[Pattern Matching](tests/pos/pattern.jo)** - Advanced pattern matching with guards and nested patterns
- **[Pattern Sequences](tests/pos/pattern-seq.jo)** - Pattern matching on sequences and lists
- **[Context Parameters](tests/pos/param-render.jo)** - Contextual abstraction and implicit parameter passing
- **[Varargs](tests/pos/vararg.jo)** - Variable-length argument lists
- **[Regular Expressions](tests/pos/regex.jo)** - Pattern-based string matching
- **[Parameter Boundaries](tests/warn/param-boundary.jo)** - Warning example showing parameter scope constraints

<a id="usage"></a>

## Usage 🎯

The `jo` command provides a unified interface to all compiler backends:

### Run Programs (Interpreter)

```bash
# Run directly (defaults to interpreter, stdlib loaded automatically)
bin/jo tests/pos/fact.jo

# Or explicitly use the run command
bin/jo run tests/pos/fact.jo
```

### Build Applications

The standard library is automatically loaded for all commands.

**Build native executable (register machine - fastest, default):**
```bash
bin/jo build tests/pos/fact.jo -o fact
./fact
```

**Build native executable (stack machine):**
```bash
bin/jo build -stack tests/pos/fact.jo -o fact
./fact
```

**Build JavaScript application:**
```bash
bin/jo build -js tests/pos/fact.jo -o fact.js
node fact.js
```

### Build Libraries

**Build a custom library (generates .sast files):**
```bash
bin/jo build-lib lib/MyLib.jo -d build/mylib
```

**Build a library that depends on another library:**
```bash
bin/jo build-lib lib/Extensions.jo -lib build/mylib -d build/extensions
```

**Use custom libraries (stdlib is still automatically loaded):**
```bash
bin/jo build app.jo -lib build/mylib -o app
./app
```

**Use multiple libraries (colon-separated, in dependency order):**
```bash
# Core depends on nothing, Utils depends on Core, App depends on both
bin/jo build app.jo -lib build/core:build/utils -o app
./app
```

**Disable automatic stdlib loading:**
```bash
bin/jo build -no-stdlib app.jo -o app
```

<a id="help"></a>

## Command Reference 📖

```
jo <file.jo>                       Run program (default)
jo run [options] <file.jo>         Run with interpreter
jo build [options] <file.jo>       Build executable/JavaScript
jo build-lib [options] <file.jo>   Build library (.sast files)
jo help                             Show help

Common options:
  -lib <dirs>              Use precompiled libraries (colon-separated, in dependency order)
                           Example: -lib build/core:build/utils
                           Stdlib is automatically loaded unless -no-stdlib is used
  -no-stdlib               Disable automatic stdlib loading
  -explicit-return-type    Require functions to have explicit return type

Build options:
  -js                      Target JavaScript (output: .js)
  -stack                   Target linux-x86 native (stack machine)
  -reg                     Target linux-x86 native (register machine, default)
  -o <file>                Output file
  -layout <c1|c2>          Memory layout (both native backends)
  -link <source>=<target>  Link function calls (e.g., -link jo.Predef.entry=Test.main)
  -runtime <dirs>          Path to runtime libraries (colon-separated, in dependency order)
  -no-detect-main          Disable automatic main function detection

Build-lib options:
  -d <dir>                 Output directory (default: current dir)
```
