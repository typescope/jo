# The Jo Programming Language 🚀

Jo is a statically typed functional programming language designed for research and teaching language implementation.

## Key Features ✨

- **Modular and composable** - No global variables; easier to compose and reuse
- **Context parameters** - Contextual abstraction, optional parameters, and implicit resolution
- **Effect system** - Fine-grained effect control with parametric effects
- **Algebraic data types** - Extensible ADTs with pattern matching
- **Pattern-oriented programming** - First-class patterns and higher-order patterns
- **Natural syntax**  - Prefix, infix, and postfix operators; two call styles `f(x)` and `f x`; indentation-based
- **Multiple backends**  - Interpreter, JavaScript, and native x86-64 Linux

## Implementation

- Written in Scala using scala-cli
- Frontend: lexer, parser, type checker with inference
- Multiple compilation backends with shared frontend
- Comprehensive test suite across all backends

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

### [The Expression Problem](https://en.wikipedia.org/wiki/Expression_problem)

```
data Expr[Lang] =
    Lit(n: Int)
  | Add(lhs: Lang, rhs: Lang)

type LangA = Expr[LangA]

def eval[T](expr: Expr[T], eval: T => Int): Int =
  match expr
    case Lit n => n
    case Add lhs rhs => (eval lhs) + (eval rhs)

def evalLangA(expr: LangA): Int =
  eval expr (e => evalLangA e)

data Mul[Lang](lhs: Lang, rhs: Lang)
type ExprExt[Lang] = Expr[Lang] | Mul[Lang]

type LangB = ExprExt[LangB]

def evalExt[T](expr: ExprExt[T], evalExt: T => Int): Int =
  match expr
    case e: Expr[T] => eval e evalExt
    case Mul lhs rhs => (evalExt lhs) * (evalExt rhs)

def evalLangB(expr: LangB): Int =
  evalExt expr (e => evalLangB e)

def main =
  val langA: LangA =
    Add
      Lit 3
      Add
        Lit 2
        Lit 5

  val langB: LangB = Mul langA (Lit 3)

  println
    intToStr
      evalLangA langA

  println
    intToStr
      evalLangB langB
```

## Build 🔨

First install [scala-cli](https://scala-cli.virtuslab.org/), then build the compiler and standard library:

```bash
# Build JAR launcher (default, faster build)
./build

# Or build native launcher (slower build, faster startup)
./build -native
```

This creates:
- `bin/jo` - Unified compiler launcher (wrapper script)
- `bin/jo.jar` - JAR executable (default build)
- `bin/jo.native` - Native executable (with `-native` flag)
- `sast/stdlib/` - Precompiled standard library (.sast files)
- `sast/runtime/js/` - JavaScript runtime library
- `sast/runtime/native/` - Native runtime library

## Usage 📖

The `jo` command provides a unified interface to all compiler backends:

### Run Programs (Interpreter)

```bash
# Run directly (defaults to interpreter, stdlib loaded automatically)
bin/jo tests/pos/fact.stk

# Or explicitly use the run command
bin/jo run tests/pos/fact.stk
```

### Build Applications

The standard library is automatically loaded for all commands.

Build native executable (register machine - fastest, default):
```bash
bin/jo build tests/pos/fact.stk -o fact
./fact
```

Build native executable (stack machine):
```bash
bin/jo build -stack tests/pos/fact.stk -o fact
./fact
```

Build JavaScript application:
```bash
bin/jo build -js tests/pos/fact.stk -o fact.js
node fact.js
```

### Build Libraries

Build a custom library (generates .sast files):
```bash
bin/jo build-lib lib/MyLib.stk -d build/mylib
```

Build a library that depends on another library:
```bash
bin/jo build-lib lib/Extensions.stk -lib build/mylib -d build/extensions
```

Use custom libraries (stdlib is still automatically loaded):
```bash
bin/jo build app.stk -lib build/mylib -o app
./app
```

Use multiple libraries (colon-separated, in dependency order):
```bash
# Core depends on nothing, Utils depends on Core, App depends on both
bin/jo build app.stk -lib build/core:build/utils -o app
./app
```

Disable automatic stdlib loading:
```bash
bin/jo build -no-stdlib app.stk -o app
```

### Command Reference

```
jo <file.stk>                       Run program (default)
jo run [options] <file.stk>         Run with interpreter
jo build [options] <file.stk>       Build executable/JavaScript
jo build-lib [options] <file.stk>   Build library (.sast files)
jo help                             Show help

Common options:
  -lib <dirs>       Use precompiled libraries (colon-separated, in dependency order)
                    Example: -lib build/core:build/utils
                    Stdlib is automatically loaded unless -no-stdlib is used
  -no-stdlib        Disable automatic stdlib loading

Build options:
  -js               Target JavaScript (output: .js)
  -stack            Target native (stack machine)
  -reg              Target native (register machine, default)
  -o <file>         Output file
  -layout <c1|c2>   Memory layout (stack machine only)

Build-lib options:
  -d <dir>          Output directory (default: current dir)
```

### Environment Variables

- `JO_HOME` - Set automatically by the `bin/jo` wrapper script to the project root directory

## Testing 🧪

Run the full test suite:
```bash
./ci
```

This runs:
- Unit tests
- Positive tests across all backends
- Warning/error message tests
- Negative tests with positional error markers
