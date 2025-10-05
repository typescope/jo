# The Jo Programming Language

Jo is a statically typed functional programming language designed for research and teaching language implementation.

## Key Features

- **Pure functional** - No global variables, immutable by default
- **Context parameters** - Contextual abstraction, optional parameters, and implicit resolution
- **Effect system** - Fine-grained effect control with parametric effects
- **Algebraic data types** - Extensible ADTs with pattern matching
- **Natural syntax** - Prefix, infix, and postfix operators; two call styles `f(x)` and `f x`; indentation-based
- **Multiple backends** - Interpreter, JavaScript, and native x86-64 Linux

## Implementation

- Written in Scala using scala-cli
- Frontend: lexer, parser, type checker with inference
- Multiple compilation backends with shared frontend
- Precompiled library format (.sast) for fast builds
- Comprehensive test suite across all backends

## Examples

### Hello world

```
fun main = println "Hello world!"
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

fun show(expr: Expr): String =
  match expr
    case Var x => x

    case Abs x t => "(\\" + x + "." + (show t) + ")"

    case App abs arg =>
      (show abs) + " " + (show arg)

fun find(x: String): Option[Value] =
  match env
    case Empty => None

    case Cons k v outer =>
      if x == k then Some(v)
      else find x with env = outer

fun eval(expr: Expr): Value =
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

fun main =
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

fun eval[T](expr: Expr[T], eval: T => Int): Int =
  match expr
    case Lit n => n
    case Add lhs rhs => (eval lhs) + (eval rhs)

fun evalLangA(expr: LangA): Int =
  eval expr (e => evalLangA e)

data Mul[Lang](lhs: Lang, rhs: Lang)
type ExprExt[Lang] = Expr[Lang] | Mul[Lang]

type LangB = ExprExt[LangB]

fun evalExt[T](expr: ExprExt[T], evalExt: T => Int): Int =
  match expr
    case e: Expr[T] => eval e evalExt
    case Mul lhs rhs => (evalExt lhs) * (evalExt rhs)

fun evalLangB(expr: LangB): Int =
  evalExt expr (e => evalLangB e)

fun main =
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

## Build

First install [scala-cli](https://scala-cli.virtuslab.org/), then build the compiler and standard library:

```bash
./build
```

This creates:
- `bin/jo` - Unified compiler launcher
- `out/stdlib/` - Precompiled standard library (.sast files)

## Usage

The `jo` command provides a unified interface to all compiler backends:

### Run Programs (Interpreter)

```bash
# Run directly (defaults to interpreter)
bin/jo tests/pos/fact.stk

# Or explicitly use the run command
bin/jo run tests/pos/fact.stk

# Run with standard library
bin/jo run app.stk -lib out/stdlib
```

### Build Applications

Build native executable (register machine - fastest, default):
```bash
bin/jo build tests/pos/fact.stk -lib out/stdlib -o fact
./fact
```

Build native executable (stack machine):
```bash
bin/jo build -stack tests/pos/fact.stk -lib out/stdlib -o fact
./fact
```

Build JavaScript application:
```bash
bin/jo build -js tests/pos/fact.stk -lib out/stdlib -o fact.js
node fact.js
```

### Build Libraries

The standard library is precompiled during the build process:
```bash
./build  # Creates bin/jo and builds stdlib to out/stdlib/
```

Build a custom library (generates .sast files):
```bash
bin/jo build-lib lib/MyLib.stk -d build/mylib
```

Build a library that depends on another library:
```bash
bin/jo build-lib lib/Extensions.stk -lib build/mylib -d build/extensions
```

Use custom libraries when building:
```bash
bin/jo build app.stk -lib build/mylib -o app
./app
```

### Command Reference

```
jo <file.stk>                       Run program (default)
jo run [options] <file.stk>         Run with interpreter
jo build [options] <file.stk>       Build executable/JavaScript
jo build-lib [options] <file.stk>   Build library (.sast files)
jo help                             Show help

Common options:
  -lib <dir>        Use precompiled library

Build options:
  -js               Target JavaScript (output: .js)
  -stack            Target native (stack machine)
  -reg              Target native (register machine, default)
  -o <file>         Output file
  -layout <c1|c2>   Memory layout (stack machine only)

Build-lib options:
  -d <dir>          Output directory (default: current dir)
```

## Testing

Run the full test suite:
```bash
./ci
```

This runs:
- Unit tests
- Positive tests across all backends
- Warning/error message tests
- Negative tests with positional error markers
