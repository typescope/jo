---
title: Introducing Jo — Secure Programming for the AI Era
date: 2026-06-04
author: The Jo Team
description: Jo is a statically typed language for fine-grained capability-based programming. It is designed to make authority confinement practical, auditable, and pleasant to use.
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
and those capabilities can be as narrowly scoped as the application requires. The
compiler tracks which capabilities code may use, so confinement is expressed in
interfaces and types rather than hidden in runtime configuration.

## Capability-Based Programming

In Jo, capabilities are ordinary parameters. They can be passed, refined,
substituted, and restricted. A function that has not received a capability
cannot use it.

Here is an example:

```jo
def foo() = println "foo"                     // inferred capability: stdout
def bar() = foo()                             // inferred capability: stdout

def qux() receives IO.stdout = println "qux"  // explicit capability: stdout

def main =
  allow none in bar()                         // error: stdout not allowed
  allow IO.stdout in bar()                    // OK
  with IO.stdout = s => pass in qux()         // redirect output
```

The compiler checks capability flow through the call graph. If a function needs
`IO.stdout`, that requirement is visible and controllable. If a call site says
`allow none`, then no hidden authority can slip through.

This gives Jo the convenience of implicit context without the security cost of
ambient globals.

## Why This Matters for AI-Generated Code

AI-generated code makes the authority problem even more acute. If an agent writes a
function for your application, you may want it to analyze data and produce a
summary, but not access the filesystem, call arbitrary HTTP endpoints, inspect
environment variables, or query other users' records.

Jo's approach is to grant only the capabilities the code should have:

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

This is the core idea behind Jo: make authority confinement a programming model.

<svg viewBox="0 0 780 370" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#888"/>
    </marker>
    <marker id="arr-b" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#4a6cf7"/>
    </marker>
    <marker id="arr-o" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#c45b00"/>
    </marker>
  </defs>
  <rect x="10" y="10" width="305" height="350" rx="10" fill="#eef2ff" stroke="#4a6cf7" stroke-width="2"/>
  <text x="162" y="38" text-anchor="middle" font-size="14" font-weight="700" fill="#4a6cf7">CONFINED WORLD</text>
  <text x="162" y="56" text-anchor="middle" font-size="11" fill="#6677bb">no FFI · confined libs only</text>
  <rect x="30" y="70" width="265" height="60" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="96" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Jo Standard Library</text>
  <text x="162" y="116" text-anchor="middle" font-size="11" fill="#777">List · Map · Option · Result · …</text>
  <rect x="30" y="170" width="265" height="72" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="196" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Interface Library</text>
  <text x="162" y="214" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">interface OrdersApi { … }</text>
  <text x="162" y="232" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">defer def aiMain(): Unit</text>
  <rect x="30" y="283" width="265" height="58" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="308" text-anchor="middle" font-size="13" font-weight="600" fill="#333">AI-Generated Code</text>
  <text x="162" y="327" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">aiMain() implements contract</text>
  <line x1="162" y1="170" x2="162" y2="130" stroke="#4a6cf7" stroke-width="1.5" marker-end="url(#arr-b)"/>
  <text x="172" y="153" font-size="10" fill="#4a6cf7">depends on</text>
  <line x1="162" y1="283" x2="162" y2="242" stroke="#4a6cf7" stroke-width="1.5" marker-end="url(#arr-b)"/>
  <text x="172" y="265" font-size="10" fill="#4a6cf7">depends on</text>
  <rect x="465" y="10" width="305" height="350" rx="10" fill="#fff4ee" stroke="#c45b00" stroke-width="2"/>
  <text x="617" y="38" text-anchor="middle" font-size="14" font-weight="700" fill="#c45b00">TRUSTED WORLD</text>
  <text x="617" y="56" text-anchor="middle" font-size="11" fill="#b07050">FFI enabled · audited</text>
  <rect x="485" y="70" width="265" height="60" rx="7" fill="white" stroke="#f0b090" stroke-width="1.5"/>
  <text x="617" y="96" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Platform Runtime</text>
  <text x="617" y="116" text-anchor="middle" font-size="11" fill="#777">FFI · syscalls · network · filesystem</text>
  <rect x="485" y="170" width="265" height="72" rx="7" fill="white" stroke="#f0b090" stroke-width="1.5"/>
  <text x="617" y="196" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Harness</text>
  <text x="617" y="214" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">UserScopedOrders(userId, db)</text>
  <text x="617" y="232" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">frameworkMain()</text>
  <line x1="617" y1="170" x2="617" y2="130" stroke="#c45b00" stroke-width="1.5" marker-end="url(#arr-o)"/>
  <text x="627" y="153" font-size="10" fill="#c45b00">depends on</text>
  <line x1="485" y1="206" x2="295" y2="206" stroke="#c45b00" stroke-width="1.5" marker-end="url(#arr-o)"/>
  <text x="390" y="199" text-anchor="middle" font-size="10" fill="#c45b00">depends on</text>
  <line x1="485" y1="235" x2="295" y2="290" stroke="#555" stroke-width="2" stroke-dasharray="6,3" marker-end="url(#arr)"/>
  <text x="390" y="282" text-anchor="middle" font-size="11" font-weight="700" fill="#555">--link</text>
</svg>

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

## Formal Foundations

Jo's design is grounded in λCC (*Lambda-CC*), a minimal calculus of contextual
capabilities with a soundness proof mechanized in Coq.

The full paper and Coq development are at
[github.com/typescope/contextual-capability](https://github.com/typescope/contextual-capability).

## Current Status

Jo is early-stage software, but it is already substantial: the compiler has an
extensive test suite, and the core capability model is ready for serious
experimentation. The language design, standard library, and tooling are still
evolving.

We encourage security-focused teams to evaluate Jo for new projects,
prototypes, internal tools, and constrained production use cases where existing
technologies cannot provide the authority confinement they need. For critical
deployments, start small, audit the capability boundaries carefully, and expect
the language and tooling to evolve.

## Development

Jo is developed by [TypeScope](https://typescope.ai/), a company focused on
making secure programming practical. We are building Jo as long-term
infrastructure: a language, compiler, standard library, documentation, and
ecosystem designed to grow steadily over many years.

Our ambition is high: to make Jo one of the best languages for writing
security-critical software, and to make secure programming feel natural rather
than burdensome.

Jo is open source under the Apache License 2.0. The repository is available at
[github.com/typescope/jo](https://github.com/typescope/jo).

The project welcomes people interested in language design, capability-based
security, secure AI systems, compilers, and practical type systems. We
especially want feedback on the security model, ergonomics, and real-world use
cases.

## Learn More

Start with the [Language Tour](/overview/language-tour) for the language
surface, or read [Two-World Architecture](/security/two-worlds) for the security model in
more detail. The detailed installation and usage material lives on
[jo-lang.org](https://jo-lang.org/).

## Feedback

We welcome feedback from language designers, security engineers, compiler
engineers, and developers building agentic systems. For concrete bugs or issues,
open an issue on [GitHub](https://github.com/typescope/jo). For community
discussion, join [r/jolang](https://www.reddit.com/r/jolang/). Security reports
should follow the process in the repository's
[SECURITY.md](https://github.com/typescope/jo/blob/main/SECURITY.md).

If the core idea resonates with you, follow the project, try the examples, and
join the discussion. Jo's mission is simple: make secure programming a joy.
