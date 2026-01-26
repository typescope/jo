<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/img/logo.svg">
    <source media="(prefers-color-scheme: light)" srcset="./docs/img/logo-black.svg">
    <img alt="Jo" src="./docs/img/logo.svg" width="33px" margin-bottom="-8px">
  </picture>
</div>

<h4 align="center">For the joy of secure programming</h4>

<!-- <p align="center"> -->
<!--   <a href="#features">Features</a> • -->
<!--   <a href="#examples">Examples</a> • -->
<!--   <a href="#usage">Usage</a> -->
<!-- </p> -->

Jo is a statically-typed object-oriented and functional language that compiles to Ruby, Python, and JavaScript. Its type system enforces capability-based security at compile time.

<a id="features"></a>

## Why Jo?

Jo is designed to solve the following authority confinement problem via its
_type system_ without resorting to sandboxing nor isolation:

> How to safely execute a 3rd party function with the guarantee that it may only
read certain rows of a database table according to access control policies, but
cannot do anything else (no abitrary http requests, file IO, etc.)?

Jo solves the security problem above in the simplest way possible without taking
away too much freedom from programmers. It brings the following benefits:

- **Authority confinement** - Confine an untrusted function to contracted authorities
- **Fine-grained control** - Attenuated authorities enable precise control, e.g. only access certain rows of a database table
- **Easy security auditing** - Compile-time checked authorities and clear security boundaries

Its ultimate goal is to make secure programming a joy.

## Key Features ✨

- **Static capability control** - Fine-grained capability control at compile-time
- **Pattern-oriented programming** - Custom pattern predicates and powerful sequence patterns
- **Context parameters** - Elegant dependency injection without global variables or frameworks

<a id="get-started"></a>

## Getting Started 🚀

```bash
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
def bar() = foo()                             // inferred capability: stdout

def baz() = println "baz"                     // inferred capability: stdout

def qux() receives IO.stdout = println "qux"  // explicit capability: only stdout

def main =
  bar() allow none                  // error: no capabilities allowed, but need stdout
  baz() allow IO.stdout             // OK
  qux() with IO.stdout = s => pass  // ignore output
```

Gives the following errors:

```log
---------- Error at tests/warn/param-allow.jo:10:3 ---------------
|   bar() allow none                  // error
|   ^^^^^
|   Parameter not allowed: stdout

The following is the trace that leads to the problem:
├──   bar() allow none                  // error	[ tests/warn/param-allow.jo:10:3 ]
│     ^^^^^
├── def bar() = foo()	[ tests/warn/param-allow.jo:2:13 ]
│               ^^^^^
└── def foo() = println "foo"	[ tests/warn/param-allow.jo:1:13 ]
                ^^^^^^^
```

### Pattern-Oriented Programming

```Scala
pattern ValidChar: Partial[Char] = case !'@' & !' '

if email is [..lhs while ValidChar, '@', ..rhs while ValidChar] then
  println "valid email: lhs = \{lhs}, rhs = \{rhs}"
else
  println "invalid email"

pattern Pos: Partial[Int] = case x if x > 0
match list
  case [..positives while Pos, ..rest] =>
    println "pos = \{positives}, rest = \{rest}"
  case _ =>

if result is Some(code) && code > 0 then
  println "Success, code = \{code}"
```

### For Secure AI

Jo's capability system can confine AI-generated code at compile time:

```Scala
//------------------ Api library ---------------------------
class Order(...)
param OrdersApi: (lastDays: Int) => List[Order] // (1)!

//------------------ Harness library ----------------------
// The signature that the AI generated code need to conform
defer def aiMain(): Unit receives OrdersApi, IO.stdout

def harnessMain() =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = (days: Int) => db.ordersFor(userId, days)

  // Capture AI code output
  val output: ArrayBuffer[String] = []
  val buffer = (s: String) => output += s

  // Compiler proves: AI code cannot access network, filesystem, or other data
  aiMain() with OrdersApi = restricted, IO.stdout = buffer allow none

  // ...

//------------------ AI generated code ----------------------
// AI-generated code: can only read orders and use stdout, nothing else
def aiAnalyze(): Unit receives OrdersApi, IO.stdout = // (6)!
  val orders = OrdersApi(30)
  summarize(orders)
```

1. The API is compiled to a separate library with **no FFI** support, the same as the standard library.
2. The harness is compiled to a separate library **with FFI** support.
3. The AI generated code is verified against the Api library **without FFI**, then linked with the harness.

The AI code cannot access the network, filesystem, or other users' data - the compiler enforces this statically. After type checking, no runtime isolation or sandboxing is needed.


### More Examples

Explore complete examples showcasing Jo's features:

- **[Demos](demos/)** - Demos showing how Jo can be used for security applications
- **[Regular Expressions](tests/pos/regex-nfa-capture.jo)** - A basic implementation of regular expressions
