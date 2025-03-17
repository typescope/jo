# Stk

Stk is a statically typed functional programming language with the following features:

- No global variables
- Context parameters for contextual abstraction, optional parameters and deep implicits
- Fine-grained effect control and effect parametricity
- Extensible algebric data types based on tagged values
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
type Lambda = #Abs(x: String, body: Expr)

type Expr = Lambda | #Var(x: String) | #App(lhs: Expr, arg: Expr)

type Env = #Empty | #Cons(k: String, v: Value, outer: Env)

type Value = { lambda: Lambda, env: Env }

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

    case abs: Lambda =>
      { lambda = abs, env = env }

    case #App abs arg =>
      val closure = eval abs
      val argValue = eval arg
      val x = closure.lambda.x
      val body = closure.lambda.body
      val env2: Env = #Cons(x, argValue, closure.env)
      eval body with env = env2

fun main =
  val id: Expr = #Abs("x", #Var "x")
  val code: Expr = #App(id, id)
  println
    show (eval code).lambda
```

### [The Expression Problem](https://en.wikipedia.org/wiki/Expression_problem)

```
type Expr =
    #Lit(n: Int)
  | #Add(lhs: Expr, rhs: Expr)

fun eval(expr: Expr): Int =
  match expr
    case #Lit n => n
    case #Add lhs rhs => (eval lhs) + (eval rhs)

type ExprExt = Expr | #Mul(lhs: ExprExt, rhs: Expr)

fun eval2(expr: ExprExt): Int =
  match expr
    case expr: Expr => eval expr
    case #Mul lhs rhs => (eval2 lhs) * (eval2 rhs)

fun main =
  val lang1: Expr =
    #Add
      #Lit 3
      #Add
        #Lit 2
        #Lit 5

  val lang2: ExprExt = #Mul lang1 (#Lit 3)

  println
    intToStr
      eval lang1

  println
    intToStr
      eval2 lang2
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
- [ ] Debugger for native backend
- [ ] Exception
- [ ] Concurrency
- [ ] Nested pattern match
- [ ] Classes
