---
title: Introducing Jo — Secure Programming for the AI Era
date: 2026-05-23
author: The Jo Team
description: Jo is a statically typed language for fine-grained capability-oriented programming. It is designed to make authority confinement practical, auditable, and pleasant to use.
---

# Introducing Jo — Secure Programming for the AI Era

Today we are introducing **[Jo](https://github.com/typescope/jo)**, a
statically typed programming language where side effects are denied by default
and authority must be granted explicitly, through fine-grained capabilities
checked by the compiler.

Modern systems execute plugins, call third-party services, run user-defined
workflows, and increasingly ask AI agents to generate and execute code. The
security question is no longer only "is this program correct?" It is also:

> How do we restrict an untrusted program to only the fine-grained capabilities
> it has been granted?

Jo is designed to make fine-grained permission confinement a property enforced
by the type system, at the level of precision real systems need: a specific
directory, a single API host, a read-only interface, or only the database rows
belonging to the current user.

## The Problem: Ambient Authority

Most mainstream languages make powerful authority available by default. A piece
of code can usually reach for the filesystem, environment variables, network,
reflection, process APIs, or foreign-function interfaces unless a runtime
sandbox stops it.

That model is convenient, but it is hard to audit. If you want to run a
third-party function and guarantee that it can only query a narrow API, not read
files or call the network, the language itself usually gives you little help.
You end up relying on containers, permissions, code review, convention, or
runtime isolation.

Jo takes a different route: authority is represented by explicit capabilities,
and those capabilities can be as narrow as the application requires. The
compiler tracks which capabilities code may use, so confinement is expressed in
interfaces and types rather than hidden in runtime configuration.

## Capability-Oriented Programming

In Jo, capabilities are ordinary parameters. They can be passed, refined,
substituted, and restricted. A function that has not received a capability
cannot use it.

Here is the basic shape:

```jo
def foo() = println "foo"                     // inferred capability: stdout
def bar() = foo()                             // inferred capability: stdout

def qux() receives IO.stdout = println "qux"  // explicit capability: stdout

def main =
  allow none in bar()                         // error: stdout not allowed
  allow IO.stdout in bar()                    // OK
  with IO.stdout = s => pass in qux()         // redirect output
```

The important part is not the syntax. The important part is the guarantee: the
compiler checks capability flow through the call graph. If a function needs
`IO.stdout`, that requirement is visible and controllable. If a call site says
`allow none`, then no hidden authority can slip through.

This gives Jo the convenience of implicit context without the security cost of
ambient globals.

## Why This Matters for AI-Generated Code

AI-generated code makes the authority problem sharper. If an agent writes a
function for your application, you may want it to analyze data and produce a
summary, but not access the filesystem, call arbitrary HTTP endpoints, inspect
environment variables, or query other users' records.

Jo's approach is to give that code only the capabilities it should have:

```jo
// API library: compiled without FFI support
interface OrdersApi
  def query(lastDays: Int): List[Order]
end

param ordersApi: OrdersApi

// AI-generated code
def aiMain(): Unit receives ordersApi, IO.stdout =
  val orders = ordersApi.query(30)
  summarize(orders)
```

The framework can implement `OrdersApi` using a real database, but expose only a
user-scoped, read-only view to the untrusted code. The AI-generated function
does not receive raw database access. It does not receive network access. It
does not receive filesystem access. The type checker enforces that boundary
before the program runs.

This is the core idea behind Jo: make authority confinement a programming model,
not an after-the-fact deployment trick.

For a concrete example, see the [data-query agent
demo](https://github.com/typescope/jo/tree/main/demos/data-query-agent), which
shows how an agent can ask flexible questions over a database while being
statically restricted to the current user's data.

## A Language, Not Just a Policy System

Jo is also intended to be pleasant as a general-purpose language. It combines
object-oriented and functional programming with a compact syntax, type
inference, classes, interfaces, algebraic data types, pattern matching, and
context parameters.

For example, Jo has reusable pattern predicates:

```jo
pattern Positive: Partial[Int] = case x if x > 0
pattern Even: Partial[Int] = case x if x % 2 == 0

match n
  case Positive & Even => "positive even"
  case Positive        => "positive odd"
  case _               => "non-positive"
```

And union types with pattern matching:

```jo
union Shape =
    Circle(radius: Float)
  | Rectangle(w: Float, h: Float)

def area(shape: Shape): Float =
  match shape
    case Circle r => 3.14 * r * r
    case Rectangle w h => w * h
```

Jo's design philosophy is to combine strong security guarantees with programmer
happiness. Security should not require fighting the language, writing
boilerplate, or moving essential reasoning into deployment configuration. The
goal is to make secure programming feel natural, expressive, and auditable.

Jo is designed for both programmers and security reviewers. Capability
boundaries are expressed in interfaces and types, so the authority a program
receives is visible at the API boundary rather than scattered through
implementation details or deployment configuration. This makes security auditing
simpler: reviewers can inspect what capabilities are granted, where they flow,
and where they are deliberately restricted.

The goal is not to make secure programming feel like a separate discipline. The
goal is to make it part of normal programming.

## Current Status

Jo is early-stage software. The compiler, language design, standard library, and
tooling are still evolving. Today, Jo can compile to Ruby and Python, with other
backend work happening in the repository.

Jo is developed by [TypeScope](https://typescope.ai/), a company focused on
making secure programming practical. We are building Jo as long-term
infrastructure: a language, compiler, standard library, documentation, and
ecosystem designed to grow steadily over many years.

Our ambition is high: to make Jo one of the best languages for writing
security-critical software, and to make secure programming feel natural rather
than burdensome.

Jo is open source under the Apache License 2.0. The repository is available at
[github.com/typescope/jo](https://github.com/typescope/jo).

The project is open for people who are interested in language design,
capability-based security, secure AI systems, compilers, and practical type
systems. We especially want feedback on the security model, ergonomics, and
real-world use cases.

## Learn More

Start with the [Language Tour](/overview/language-tour) for the language
surface, or read [Jo's Solution](/security/solution) for the security model in
more detail. The detailed installation and usage material lives on
[jo-lang.org](https://jo-lang.org/).

We welcome feedback from language designers, security engineers, compiler
engineers, and developers building agentic systems. For concrete bugs or issues,
open an issue on [GitHub](https://github.com/typescope/jo). For community
discussion, join [r/jolang](https://www.reddit.com/r/jolang/). Security reports
should follow the process in the repository's
[SECURITY.md](https://github.com/typescope/jo/blob/main/SECURITY.md).

If the core idea resonates with you, follow the project, try the examples, and
join the discussion. Jo's mission is simple: make secure programming a joy.
>>>>>>> d2cbfd26 (Improve blog)
