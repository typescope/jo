<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/img/logo.svg">
    <source media="(prefers-color-scheme: light)" srcset="./docs/img/logo-black.svg">
    <img alt="Jo" src="./docs/img/logo.svg" width="40px">
  </picture>

  <h3>Jo Programming Language</h3>
  <p>Secure programming for the AI era</p>

  [![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
  [![Docs](https://img.shields.io/badge/docs-jo--lang.org-teal.svg)](https://jo-lang.org)
</div>

---

Jo is a statically typed language where **capabilities are explicit, statically tracked, and enforced by the compiler**. Jo compiles to Ruby, Python and JavaScript.

> **Project status:** Early-stage. The compiler, standard library, and toolchain are ready for serious experimentation. APIs and language details may still change.

## Why Jo?

AI agents generate code that runs inside your platform. That code can — unless you prevent it — reach for the network, read arbitrary files, or query other users' data. Runtime sandboxes help, but they operate at the wrong level: they can block syscalls or filesystem paths, but they cannot enforce "access only this user's rows".

Jo enforces capability boundaries at the type level, before the program runs. A function that has not received a capability cannot use it. The compiler proves this transitively through the entire call graph.

## Language Highlights

### Static capability control

```scala
def foo() = println "foo"                     // inferred capability: stdout
def bar() = foo()                              // inferred capability: stdout

def qux() receives IO.stdout = println "qux" // explicit: only stdout

def main =
  allow none in bar()                         // error: no capabilities allowed
  allow IO.stdout in bar()                    // OK
  with IO.stdout = s => pass in qux()         // redirect output
```

```
---------- Error at main.jo:5:3 ---------------
|   allow none in bar()
|                 ^^^^^
|   Parameter not allowed: stdout

The following is the trace that leads to the problem:
├──   allow none in bar()     [ main.jo:5:3 ]
│                   ^^^^^
├── def bar() = foo()         [ main.jo:2:13 ]
│               ^^^^^
└── def foo() = println "foo" [ main.jo:1:13 ]
                ^^^^^^^
```

### Pattern-oriented programming

Named, reusable pattern predicates compose with logical operators:

```scala
pattern Positive: Partial[Int] = case x if x > 0

match list
case [..positives while Positive, ..rest] =>
  println "pos = \{positives}, rest = \{rest}"

case _ => pass

if result is Some(code) && code > 0 then
  println "Success, code = \{code}"

// enable option "s" to allow . to match new line
if message is `(?s)<code>(?<prog>.*)</code>` then
  println prog
```

## Confining AI-Generated Code

The two-world architecture separates confined code (no FFI, checked against capability interfaces only) from trusted code (FFI allowed, implements and provides capabilities):

```scala
//--- Interface library (confined, no FFI) ---
param ordersApi: OrdersApi
defer def aiMain(): Unit receives ordersApi, IO.stdout

//--- Framework harness (trusted, FFI allowed) ---
def frameworkMain() =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = new UserScopedOrders(userId, db)  // attenuated: user-scoped, read-only
  val buffer = (s: String) => output += s

  allow none in
    with ordersApi = restricted, IO.stdout = buffer in aiMain()

//--- AI-generated code (confined, no FFI) ---
def aiMain(): Unit receives ordersApi, IO.stdout =
  val orders = ordersApi.query(30)
  summarize(orders)
```

`allow none` is a compile-time proof: `aiMain()` uses no capabilities beyond what it declared. The AI code cannot access the network, filesystem, or other users' data.

See the [security documentation](https://jo-lang.org/security/security-problem) for the full model.

## Try Jo

```bash
curl -sSf https://jo-lang.org/install.sh | sh
```

The installer downloads the compiler to `~/.jo/compilers/<version>/` and creates a launcher at `~/.local/bin/jo`. Add `~/.local/bin` to `PATH` if it is not already there.

Full installation instructions and a getting-started guide are at **[jo-lang.org](https://jo-lang.org)**.

## Learn More

| | |
|---|---|
| [Language Tour](https://jo-lang.org/overview/language-tour) | Overview of Jo's features with examples |
| [Security Model](https://jo-lang.org/security/two-worlds) | How capability enforcement works |
| [Language Reference](https://jo-lang.org/language/design-principles) | Types, expressions, patterns, definitions |
| [Build Tool](https://jo-lang.org/usage/getting-started) | Project setup, dependencies, commands |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, contribution guidelines, and the DCO sign-off requirement. Bug reports, language design discussions, and pull requests are welcome.

Security issues should be reported privately — see [SECURITY.md](SECURITY.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).

Jo is developed and maintained by [TypeScope](https://typescope.ai).
