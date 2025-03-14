# Stk

Stk is a statically typed functional programming language with the following features:

- No global variables
- Context parameters for contextual abstraction, optional parameters and deep implicits
- Fine-grained effect control and effect parametricity
- Flexible prefix, infix and postfix functions
- Support two call/pattern syntax `f(x)` and `f x`
- Indented syntax to avoid semicolons, parentheses and braces

The language is intended for research in programming languages and teaching
language implementation.

## Examples

### Hello world

```
fun main = println "Hello world!"
```

### Lambda Calculus

```
type Expr = enum {
  Var(x: String)
  Abs(x: String, body: Expr)
  App(lhs: Expr, arg: Expr)
}

type Env = enum {
  Empty
  Cons(k: String, v: Value, outer: Env)
}

type Value = { lambda: Expr, env: Env }

param env: Env = #Empty // environment for variables

fun show(expr: Expr): String =
  match expr
    case #Var x => x

    case #Abs x t => "(\\" + x + "." + (show t) + ")"

    case #App abs arg =>
      (show abs) + " " + (show arg)

fun find(x: String): Option[Value] =
  match env
    case #Empty => #None

    case #Cons k v outer =>
      if x == k then #Some(v)
      else find x with env = outer

fun eval(expr: Expr): Value =
  match expr
    case #Var x =>
      match find x
        case #None => abort ("Unfound variable: " + x)
        case #Some v => v
      end

    case #Abs _ _ =>
      { lambda = expr, env = env }

    case #App abs arg =>
      val closure = eval abs
      val argValue = eval arg
      match closure.lambda
        case #Abs(x, body) =>
          val env3: Env = #Cons(x, argValue, closure.env)
          eval body with env = env3

        case e =>
          abort ("Expect lambda, found = " + (show e))
      end

fun main =
  val id: Expr = #Abs("x", #Var "x")
  val code: Expr = #App(id, id)
  println
    show (eval code).lambda
```

## Build

```
./build
```

## Run

Run the interpreter:

``` shell
bin/run.native tests/pos/fact.stk
```

Run the compiler targeting Linux/x86:

``` shell
bin/regc.native tests/pos/fact.stk -o fact
./fact
```

Run the compiler targeting JavaScript:

``` shell
bin/jsc.native tests/pos/fact.stk -o fact.js
node fact.js
```

## Roadmap

- [x] Interpreter
- [x] JavaScript Backend
- [x] Native Backend based on stack machine
- [x] Native Backend based on register machine
- [x] ADT and pattern match
- [x] Records
- [x] Basic types: Bool, Byte, Char, Int, String, File
- [x] Objects and object types
- [x] First-class functions
- [x] Polymorphic functions
- [x] Type lambdas
- [x] Context parameters
- [ ] Coercion semantics for records and objects
- [ ] GC for native backend
- [ ] Exception
- [ ] Concurrency
- [ ] Nested pattern match
- [ ] Classes
