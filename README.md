<h1 align="center"> 🛡 The Jo Programming Language️</h1>
<h3 align="center">For the joy of secure programming</h3>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#examples">Examples</a> •
  <a href="#usage">Usage</a>
</p>

---

Jo is a programming language with capability-based security built into its type system.


- **API confinement** - Customize language runtime to only expose platform APIs
- **Fine-grained control** - Precise control of authority, e.g. only access certain rows of a data table
- **Easy security auditing** - Statically checked ambient authorities and clear security boundaries

<a id="features"></a>

## Key Features ✨

- **Extensible runtime** - Extend and customize the language runtime with a Jo library
- **No global variables** - Safe and easy to compose and for reuse
- **Context parameters** - Contextual abstraction, optional parameters, and implicit resolution
- **Static capability control** - Fine-grained capability control at compile-time
- **Algebraic data types** - Extensible ADTs with pattern matching
- **Pattern-oriented programming** - Custom pattern predicates and powerful sequence patterns
- **Natural syntax**  - Custom operators and indentation-based quiet syntax
- **Multiple backends**  - Ruby, JavaScript, Python, and more are coming (Java)

<a id="get-started"></a>

## Getting Started 🚀

```bash
# Run a program (interpreter)
bin/jo tests/pos/hello.jo

# Build Ruby (default)
bin/jo build tests/pos/hello.jo -o hello.rb
ruby hello.rb

# Build JavaScript
bin/jo build -js tests/pos/hello.jo -o hello.js
node hello.js

# Build Python
bin/jo build -python tests/pos/hello.jo -o hello.py
python hello.py
```

<a id="examples"></a>

## Examples 💡

### Hello world

```Scala
def main = println "Hello world!"
```

### Capability-Oriented Programming

```Scala
def foo() = println "foo"                     // inferred capability: stdout
def bar() = foo                               // inferred capability: stdout

def baz() = println "baz"                     // inferred capability: stdout

def qux() receives IO.stdout = println "qux"  // explicit capability: stdout

def main =
  bar allow none                  // error
  baz allow IO.stdout             // OK
  qux with IO.stdout = s => pass  // ignore output
```

Gives the following errors:

```
---------- Error at tests/warn/param-allow.jo:10:3 ---------------
|   bar allow none                  // error
|   ^^^
|   Parameter not allowed: stdout

The following is the trace that leads to the problem:
├──   bar allow none                  // error	[ tests/warn/param-allow.jo:10:3 ]
│     ^^^
├── def bar() = foo	[ tests/warn/param-allow.jo:2:13 ]
│               ^^^
└── def foo() = println "foo"	[ tests/warn/param-allow.jo:1:13 ]
                ^^^^^^^
```

### Pattern-Oriented Programming

```Scala
def checkEmail(email: String): Unit =
  pattern ValidChar: Partial[Char] = case !'@' & !' '

  if email is [..lhs while ValidChar, '@', ..rhs while ValidChar] then
    println "valid email: lhs = \{lhs}, rhs = \{rhs}"

  else
    println "invalid email"
```

### ADT and Context Parameters

[Lambda Calculus](https://en.wikipedia.org/wiki/Lambda_calculus)

```Scala
union Expr =
    Abs(x: String, body: Expr)
  | Var(x: String)
  | App(lhs: Expr, arg: Expr)

union Env = Empty | Cons(k: String, v: Value, outer: Env)

class Value(lambda: Abs, env: Env)

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

- **[Demos](demos/)** - Demos showing how Jo can be used for security applications
- **[Regular Expressions](tests/pos/regex-nfa-capture.jo)** - A basic implementation of regular expressions
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

**Build Ruby application (default):**
```bash
bin/jo build tests/pos/fact.jo -o fact.rb
ruby fact.rb
```

**Build JavaScript application:**
```bash
bin/jo build -js tests/pos/fact.jo -o fact.js
node fact.js
```

**Build Python application:**
```bash
bin/jo build -python tests/pos/fact.jo -o fact.py
python fact.py
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

```text
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
  -ruby                    Target Ruby (default, output: .rb)
  -js                      Target JavaScript (output: .js)
  -python                  Target Python (output: .py)
  -o <file>                Output file
  -link <source>=<target>  Link function calls (e.g., -link jo.Predef.entry=Test.main)
  -runtime <dirs>          Path to runtime libraries (colon-separated, in dependency order)
  -no-detect-main          Disable automatic main function detection

Build-lib options:
  -d <dir>                 Output directory (default: current dir)
```
